use crate::source::{ChannelCount, Sample, SampleRate, Source};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock},
};

/// The filter tables depend only on the pair of rates (reduced by their GCD), and building one is
/// expensive: ~1 ms for 22050 -> 48000 and 16+ ms for 32728 -> 48000 (it needs hundreds of
/// thousands of sinc evaluations). A game starts a sound many times, and it did all of that on the
/// game's thread each time, which showed up as a hitch whenever a cry or effect began. Built once
/// per pair and shared.
type KaiserTable = Arc<[Box<[f32]>]>;

fn kaiser_cache() -> &'static Mutex<HashMap<(u32, u32), KaiserTable>> {
    static CACHE: OnceLock<Mutex<HashMap<(u32, u32), KaiserTable>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Implementation of a PQF resampler. Construct with: Resampler::new(source, source_rate, dest_rate)
/// Once constructed, it will behave as a Source object which outputs samples at the target sample rate.
pub struct Resampler<S>
where
    S: Source,
{
    source: S,
    from: u32,
    to: u32,
    dest_rate: SampleRate, // Actual output rate - different from `to` because that is scaled down by GCD
    left_offset: usize,
    kaiser_values: Arc<[Box<[f32]>]>,
    filter_1: Box<[Sample]>,
    filter_2: Box<[Sample]>,

    // The size of the entire filter including both buffers
    whole_filter_size: usize,

    // The size of each individual buffer
    buffer_size: usize,

    // How many input samples were already discarded before the start of the current filter
    input_offset: u64,

    // How many output samples have been written so far
    output_count: usize,

    // The last valid sample in the filter, if the source ended and wasn't able to fill the entire buffer
    last_sample: Option<usize>,
}

impl<S: Source> Resampler<S> {
    pub fn new(mut source: S, dest_rate: SampleRate) -> Self {
        #[inline]
        fn gcd(a: u32, b: u32) -> u32 {
            if b == 0 { a } else { gcd(b, a % b) }
        }

        fn sinc_filter(left: u32, gain: f64, cutoff: f64, i: u32) -> f64 {
            #[inline]
            fn sinc(x: f64) -> f64 {
                if x == 0.0 {
                    1.0
                } else {
                    let x_pi = x * std::f64::consts::PI;
                    x_pi.sin() / x_pi
                }
            }

            #[inline]
            fn bessel_i0(x: f64) -> f64 {
                // Just trust me on this one
                let ax = x.abs();
                if ax < 3.75 {
                    let y = (x / 3.75).powi(2);
                    1.0 + y
                        * (3.5156229
                            + y * (3.0899424 + y * (1.2067492 + y * (0.2659732 + y * (0.0360768 + y * 0.0045813)))))
                } else {
                    let y = 3.75 / ax;
                    (ax.exp() / ax.sqrt())
                        * (0.39894228
                            + y * (0.01328592
                                + y * (0.00225319
                                    + y * (-0.00157565
                                        + y * (0.00916281
                                            + y * (-0.02057706
                                                + y * (0.02635537 + y * (-0.01647633 + y * 0.00392377))))))))
                }
            }

            #[inline]
            fn kaiser(k: f64) -> f64 {
                if !(-1.0..=1.0).contains(&k) {
                    0.0
                } else {
                    // 6.20426 is the Kaiser beta value for a rejection of 65 dB.
                    // The magic number at the end is bessel_i0(6.20426)
                    bessel_i0(6.20426 * (1.0 - k.powi(2)).sqrt()) / 81.0332923199
                }
            }

            let left = f64::from(left);
            let x = f64::from(i) - left;
            kaiser(x / left) * 2.0 * gain * cutoff * sinc(2.0 * cutoff * x)
        }

        #[inline]
        fn kaiser_order(transition_width: f64) -> usize {
            // Calculate kaiser order for given transition width and a rejection of 65 dB.
            // Kaiser's original formula for this is: (rejection - 7.95) / (2.285 * 2 * pi * width)
            ((65.0 - 7.95) / (2.285 * 2.0 * std::f64::consts::PI * transition_width)).ceil() as usize
        }

        let src = u32::from(source.sample_rate());
        let dst = u32::from(dest_rate);
        let gcd = gcd(src, dst);
        let from = src / gcd;
        let to = dst / gcd;

        let downscale_factor = f64::from(to.max(from));
        let cutoff = 0.475 / downscale_factor;
        let transition_width = 0.05 / downscale_factor;

        let kaiser_value_count = kaiser_order(transition_width) + 1;
        let left_offset = kaiser_value_count / 2;

        let step = to as usize;
        let cached = kaiser_cache().lock().ok().and_then(|cache| cache.get(&(from, to)).cloned());
        let kaiser_values: KaiserTable = match cached {
            Some(table) => table,
            None => {
                let table: KaiserTable = (0..step).map(|start_val| {
                    (start_val..kaiser_value_count).step_by(step).rev().map(|i| {
                        sinc_filter(left_offset as _, downscale_factor, cutoff, i as _) as f32
                    }).collect::<Vec<_>>().into_boxed_slice()
                }).collect::<Vec<_>>().into();
                if let Ok(mut cache) = kaiser_cache().lock() {
                    cache.insert((from, to), table.clone());
                }
                table
            },
        };

        let filter_samples = ((kaiser_value_count + to as usize) / to as usize) * usize::from(source.channel_count().get());
        let mut filter_1 = Vec::with_capacity(filter_samples);
        let mut filter_2 = Vec::with_capacity(filter_samples);

        unsafe {
            filter_1.set_len(filter_samples);
            filter_2.set_len(filter_samples);
        }

        let last_sample = Self::init_filter(&mut source, &mut filter_1, &mut filter_2);

        Self {
            source,
            from,
            to,
            dest_rate,
            left_offset,
            kaiser_values,
            filter_1: filter_1.into_boxed_slice(),
            filter_2: filter_2.into_boxed_slice(),
            whole_filter_size: filter_samples * 2,
            buffer_size: filter_samples,
            input_offset: 0,
            output_count: 0,
            last_sample,
        }
    }
}

impl<S: Source> Source for Resampler<S> {
    #[inline]
    fn channel_count(&self) -> ChannelCount {
        self.source.channel_count()
    }

    #[inline]
    fn sample_rate(&self) -> SampleRate {
        self.dest_rate
    }

    fn write_samples(&mut self, buffer: &mut [Sample]) -> usize {
        let from = u64::from(self.from);
        let to = u64::from(self.to);
        let channels = usize::from(self.channel_count().get());

        for (i, s) in buffer.iter_mut().enumerate() {
            // Tells us which channel we're currently looking at in the output data.
            // We should only be using input data from the same channel.
            let channel = self.output_count % channels;

            // Here, we calculate which input sample to start at and which set of kaiser values to use.
            // We first calculate an upscaled sample index ("start"), then take both its division and modulo
            // with our target sample rate. The int-division gives us a sample index in input data, and
            // the modulo gives us our kaiser offset.
            let start = (self.left_offset + (from as usize * (self.output_count / channels))) as u64;
            let kaiser_index = start % to;
            let input_index = start / to;

            // input_index doesn't respect multi-channel tracks and ignores our filter setup, so now we'll
            // translate it into a sample in our filter. This is actually the index of the LAST sample,
            // inclusive, which we want to operate on.
            let mut sample_index = (input_index * channels as u64) + channel as u64 - self.input_offset;

            // And now get a set of kaiser values to multiply by the filter.
            let kaiser_values = unsafe {
                // SAFETY: self.kaiser_values is a boxed slice with length `to`, and
                // kaiser_index is calculated as a modulo of `to`
                self.kaiser_values.get_unchecked(kaiser_index as usize)
            };

            // sample_index is our last (inclusive) sample, so if it's beyond the length of our filter,
            // then we need new data.
            // However, don't try to get new data if the source has already been emptied (ie. we have a last_sample).
            while (sample_index >= self.whole_filter_size as u64) && self.last_sample.is_none() {
                // Read new samples into filter 1, which is now fully depleted, so it's fine to overwrite it.
                let len = self.source.write_samples(&mut self.filter_1);
                // Handle our source being empty
                if len != self.filter_1.len() {
                    self.last_sample = Some(self.buffer_size + len);
                }
                // Swap filters 1 and 2. Now the new samples are in filter_2. Turbofish here guarantees O(1) ptr swap
                std::mem::swap::<Box<_>>(&mut self.filter_1, &mut self.filter_2);
                // And finally set our sample index back and input offset forward appropriately.
                let sample_count = self.buffer_size as u64;
                sample_index -= sample_count;
                self.input_offset += sample_count;
            }

            // If we are past the end of our audio, exit early and indicate how much of the buffer we filled
            if let Some(end) = self.last_sample {
                if sample_index as usize + (self.left_offset * channels) > end {
                    return i
                }
            }

            // And at last we can calculate an output sample.
            *s = self.get_sample(kaiser_values, channels, channel, sample_index as usize);

            self.output_count += 1;
        }

        buffer.len()
    }

    fn reset(&mut self) {
        self.source.reset();
        self.last_sample = Self::init_filter(&mut self.source, &mut self.filter_1, &mut self.filter_2);
        self.input_offset = 0;
        self.output_count = 0;
    }
}

impl<S> Resampler<S> where S: Source {
    // Calculates an output sample at the given sample_index.
    // sample_index is the index of the LAST (inclusive) sample we want to use in the calculation.
    // That's because, strangely, it's the most efficient way of calculating a stream position.
    #[inline(always)]
    fn get_sample(&self, kaiser_values: &[f32], channels: usize, channel: usize, sample_index: usize) -> Sample {
        unsafe {
            let kaiser_offset = kaiser_values.len() - 1;
            let (filter_skip_1, kaiser_skip) = {
                match sample_index.checked_sub(kaiser_offset * channels) {
                    Some(x) => (x, 0),
                    None => (channel, kaiser_offset - (sample_index / channels)),
                }
            };

            let filter_skip_2 = {
                match sample_index.checked_sub(kaiser_offset * channels + self.buffer_size) {
                    Some(x) => x,
                    None => channel,
                }
            };

            let mut output: Sample = 0.0;
            let mut f1_ptr = self.filter_1.as_ptr().add(filter_skip_1);
            let mut f2_ptr = self.filter_2.as_ptr().add(filter_skip_2);
            let mut kaiser_ptr = kaiser_values.as_ptr().add(kaiser_skip);
            let f1_end = self.filter_1.as_ptr().add(self.buffer_size);
            let f2_end = self.filter_2.as_ptr().add(self.buffer_size);
            let kaiser_end = kaiser_values.as_ptr().add(kaiser_values.len());

            while f1_ptr < f1_end && kaiser_ptr < kaiser_end {
                output += (*f1_ptr) * (*kaiser_ptr);
                kaiser_ptr = kaiser_ptr.add(1);
                f1_ptr = f1_ptr.add(channels);
            }

            while f2_ptr < f2_end && kaiser_ptr < kaiser_end {
                output += (*f2_ptr) * (*kaiser_ptr);
                kaiser_ptr = kaiser_ptr.add(1);
                f2_ptr = f2_ptr.add(channels);
            }

            output
        }
    }

    // Initializes a filter from a Sample, returning the optional last_sample
    fn init_filter(source: &mut S, filter_1: &mut [Sample], filter_2: &mut [Sample]) -> Option<usize> {
        let len = source.write_samples(filter_1);
        if len == filter_1.len() {
            let len = source.write_samples(filter_2);
            if len == filter_2.len() { None } else { Some(len) }
        } else {
            Some(len)
        }
    }
}

/// Builds the filter for a pair of rates ahead of time, so the first sound that needs it does not
/// pay for it while the game is running.
pub fn prepare(source_rate: SampleRate, dest_rate: SampleRate) {
    struct Silent(SampleRate);
    impl Source for Silent {
        fn channel_count(&self) -> ChannelCount {
            ChannelCount::new(1).unwrap()
        }
        fn sample_rate(&self) -> SampleRate {
            self.0
        }
        fn write_samples(&mut self, _buffer: &mut [Sample]) -> usize {
            0
        }
        fn reset(&mut self) {}
    }
    let _ = Resampler::new(Silent(source_rate), dest_rate);
}
