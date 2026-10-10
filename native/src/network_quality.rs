//! Local presentation of the pinned tsclientlib statistics. No protocol polling.
use serde::Serialize;
use std::{
    collections::VecDeque,
    time::{Duration, Instant, SystemTime},
};
use tsclientlib::ConnectionStats;

const WINDOW: u64 = 30;
const RECOVERY: Duration = Duration::from_secs(5);
// Statistics arrive every second; allow a few missed one-second RTT probes.
const FRESHNESS: Duration = Duration::from_secs(2);
const RTT_FRESHNESS: Duration = Duration::from_secs(5);
const AXIS_STEPS: [f64; 9] = [10.0, 50.0, 100.0, 200.0, 300.0, 400.0, 500.0, 750.0, 1000.0];

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Grade {
    Good,
    Fair,
    Poor,
}

fn grade(value: f64, fair: f64, poor: f64) -> Grade {
    if value < fair {
        Grade::Good
    } else if value < poor {
        Grade::Fair
    } else {
        Grade::Poor
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Sample {
    second: u64,
    rtt_ms: f64,
    grade: Grade,
}

#[derive(Default, Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Reading {
    rtt_ms: Option<f64>,
    deviation_ms: Option<f64>,
    packet_loss_percent: Option<f64>,
    rtt_grade: Option<Grade>,
    deviation_grade: Option<Grade>,
    packet_loss_grade: Option<Grade>,
}

impl Reading {
    fn from_stats(rtt: Duration, deviation: Duration, loss_fraction: f32) -> Option<Self> {
        if !loss_fraction.is_finite() || loss_fraction < 0.0 {
            return None;
        }
        let rtt = rtt.as_secs_f64() * 1000.0;
        let dev = deviation.as_secs_f64() * 1000.0;
        // Compare the fraction in its original f32 precision at 1% / 3%.
        // Converting 0.01f32 to f64 before comparing to 1.0% moves that boundary.
        Some(Self {
            rtt_ms: Some(rtt),
            deviation_ms: Some(dev),
            packet_loss_percent: Some(f64::from(loss_fraction) * 100.0),
            rtt_grade: Some(grade(rtt, 100.0, 200.0)),
            deviation_grade: Some(grade(dev, 50.0, 100.0)),
            packet_loss_grade: Some(grade(
                f64::from(loss_fraction),
                f64::from(0.01f32),
                f64::from(0.03f32),
            )),
        })
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Quality {
    #[serde(flatten)]
    reading: Reading,
    icon_grade: Option<Grade>,
    axis_max_ms: f64,
    now_second: u64,
    samples: VecDeque<Sample>,
}

pub(crate) struct History {
    origin: Instant,
    wall_baseline: (Instant, SystemTime),
    sleep_time: Duration,
    last_event: Option<Instant>,
    rtt_measurements: Option<u64>,
    last_rtt: Option<Instant>,
    icon_recovery: Option<(Grade, Instant)>,
    axis_recovery: Option<(f64, Instant)>,
    quality: Quality,
}

// Both downward transitions require continuous observations; increases are immediate.
fn buffered<T: Copy + PartialOrd>(
    current: T,
    wanted: T,
    recovery: &mut Option<(T, Instant)>,
    now: Instant,
) -> T {
    if wanted >= current {
        *recovery = None;
        return wanted;
    }
    let (candidate, since) = recovery.get_or_insert((wanted, now));
    if wanted > *candidate {
        *candidate = wanted;
        *since = now;
    }
    if now.duration_since(*since) >= RECOVERY {
        let result = *candidate;
        *recovery = None;
        result
    } else {
        current
    }
}

fn axis_limit(peak: f64) -> f64 {
    let needed = peak * 1.1;
    AXIS_STEPS
        .into_iter()
        .find(|limit| *limit >= needed)
        .unwrap_or_else(|| needed.ceil())
}

impl History {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            origin: now,
            wall_baseline: (now, SystemTime::now()),
            sleep_time: Duration::ZERO,
            last_event: None,
            rtt_measurements: None,
            last_rtt: None,
            icon_recovery: None,
            axis_recovery: None,
            quality: Quality {
                reading: Reading::default(),
                icon_grade: None,
                axis_max_ms: AXIS_STEPS[0],
                now_second: 0,
                samples: VecDeque::new(),
            },
        }
    }

    fn advance(&mut self, now: Instant, wall: SystemTime) {
        // Mobile monotonic clocks can pause during system sleep. A forward wall
        // clock gap must not leave pre-suspend bars or recovery time on screen.
        let monotonic_gap = now.duration_since(self.wall_baseline.0);
        let sleep_gap = wall
            .duration_since(self.wall_baseline.1)
            .ok()
            .filter(|gap| *gap > monotonic_gap + FRESHNESS);
        if let Some(gap) = sleep_gap {
            self.sleep_time = self.sleep_time.saturating_add(gap - monotonic_gap);
            self.last_event = None;
            self.last_rtt = None;
        }
        self.wall_baseline = (now, wall);
        self.quality.now_second = self.second(now);
        self.quality
            .samples
            .retain(|sample| self.quality.now_second.saturating_sub(sample.second) < WINDOW);
        if sleep_gap.is_some() && self.quality.samples.is_empty() {
            self.quality.axis_max_ms = AXIS_STEPS[0];
        }
        if self
            .last_event
            .is_none_or(|last| now.duration_since(last) > FRESHNESS)
        {
            self.quality.reading = Reading::default();
        }
        if self.quality.reading.rtt_ms.is_none() || !self.rtt_fresh(now) {
            self.quality.reading.rtt_ms = None;
            self.quality.reading.deviation_ms = None;
            self.quality.reading.rtt_grade = None;
            self.quality.reading.deviation_grade = None;
            self.quality.icon_grade = None;
            self.icon_recovery = None;
            self.axis_recovery = None;
        }
    }

    fn second(&self, now: Instant) -> u64 {
        now.duration_since(self.origin)
            .saturating_add(self.sleep_time)
            .as_secs()
    }

    fn rtt_fresh(&self, now: Instant) -> bool {
        self.last_rtt
            .is_some_and(|last| now.duration_since(last) <= RTT_FRESHNESS)
    }

    pub(crate) fn update(&mut self, stats: Option<&ConnectionStats>, now: Instant) -> &Quality {
        self.advance(now, SystemTime::now());
        self.last_event = Some(now);
        let mut new_measurement = false;
        if let Some(stats) = stats {
            if self.rtt_measurements != Some(stats.rtt_measurements) {
                self.rtt_measurements = Some(stats.rtt_measurements);
                self.last_rtt = stats.rtt_measured_at.map(|time| time.into_std());
                new_measurement = self.last_rtt.is_some();
            }
        }
        let reading = stats.and_then(|stats| {
            // get_packetloss() returns a fraction for the library's current window.
            Reading::from_stats(stats.rtt, stats.rtt_dev, stats.get_packetloss())
        });
        if let Some(mut reading) = reading {
            if !self.rtt_fresh(now) {
                reading.rtt_ms = None;
                reading.deviation_ms = None;
                reading.rtt_grade = None;
                reading.deviation_grade = None;
                self.quality.reading = reading;
                self.quality.icon_grade = None;
                self.icon_recovery = None;
                self.axis_recovery = None;
                return &self.quality;
            }
            let worst = reading
                .rtt_grade
                .max(reading.deviation_grade)
                .max(reading.packet_loss_grade)
                .unwrap();
            self.quality.icon_grade = Some(match self.quality.icon_grade {
                Some(current) => buffered(current, worst, &mut self.icon_recovery, now),
                None => worst,
            });
            // Publish only accepted measurements, including unchanged or zero RTTs.
            if new_measurement {
                let sample = Sample {
                    second: self.second(self.last_rtt.unwrap()),
                    rtt_ms: reading.rtt_ms.unwrap(),
                    grade: reading.rtt_grade.unwrap(),
                };
                match self.quality.samples.back_mut() {
                    Some(last) if last.second == sample.second => *last = sample,
                    _ => self.quality.samples.push_back(sample),
                }
            }
            self.quality.reading = reading;
            let peak = self
                .quality
                .samples
                .iter()
                .map(|sample| sample.rtt_ms)
                .fold(0.0, f64::max);
            self.quality.axis_max_ms = buffered(
                self.quality.axis_max_ms,
                axis_limit(peak),
                &mut self.axis_recovery,
                now,
            );
        } else {
            self.quality.reading = Reading::default();
            self.quality.icon_grade = None;
            self.icon_recovery = None;
            self.axis_recovery = None;
        }
        &self.quality
    }

    // Reuse the existing lifecycle clock only to age stale display data, never to
    // fetch statistics or add samples. Normal updates come from StatsUpdated.
    pub(crate) fn expire(&mut self, now: Instant) -> Option<&Quality> {
        if self
            .last_event
            .is_some_and(|last| now.duration_since(last) > FRESHNESS)
            || self
                .last_rtt
                .is_some_and(|last| now.duration_since(last) > RTT_FRESHNESS)
        {
            if self.second(now) == self.quality.now_second {
                return None;
            }
            self.advance(now, SystemTime::now());
            Some(&self.quality)
        } else {
            None
        }
    }

    pub(crate) fn resume(&mut self, now: Instant) -> &Quality {
        self.advance(now, SystemTime::now());
        &self.quality
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    impl History {
        fn measure(&mut self, rtt: f64, dev: f64, now: Instant) -> &Quality {
            let mut stats = ConnectionStats::default();
            stats.rtt = Duration::from_secs_f64(rtt / 1000.0);
            stats.rtt_dev = Duration::from_secs_f64(dev / 1000.0);
            stats.rtt_measurements = self.rtt_measurements.unwrap_or(0).wrapping_add(1);
            stats.rtt_measured_at = Some(now.into());
            self.update(Some(&stats), now)
        }
    }
    #[test]
    fn raw_thresholds_units_defaults_and_real_zeros() {
        let reading =
            Reading::from_stats(Duration::from_millis(42), Duration::from_millis(6), 0.008)
                .unwrap();
        assert!((reading.packet_loss_percent.unwrap() - 0.8).abs() < 0.000001);
        for (loss, expected) in [
            (0.00999, Grade::Good),
            (0.01, Grade::Fair),
            (0.02999, Grade::Fair),
            (0.03, Grade::Poor),
            (0.04, Grade::Poor),
        ] {
            assert_eq!(
                Reading::from_stats(Duration::ZERO, Duration::ZERO, loss)
                    .unwrap()
                    .packet_loss_grade,
                Some(expected)
            );
        }
        assert!(Reading::from_stats(Duration::ZERO, Duration::ZERO, f32::NAN).is_none());
        for (value, expected) in [
            (99.99, Grade::Good),
            (100.0, Grade::Fair),
            (199.99, Grade::Fair),
            (200.0, Grade::Poor),
        ] {
            assert_eq!(grade(value, 100.0, 200.0), expected);
        }
        for (value, expected) in [
            (30.0, Grade::Good),
            (49.96, Grade::Good),
            (50.0, Grade::Fair),
            (70.0, Grade::Fair),
            (99.96, Grade::Fair),
            (100.0, Grade::Poor),
            (120.0, Grade::Poor),
        ] {
            let r = Reading::from_stats(
                Duration::from_millis(30),
                Duration::from_secs_f64(value / 1000.0),
                0.0,
            )
            .unwrap();
            assert_eq!(r.deviation_grade, Some(expected));
            assert_eq!(r.rtt_grade, Some(Grade::Good));
            assert_eq!(r.packet_loss_grade, Some(Grade::Good));
        }
        let now = Instant::now();
        let mut history = History::new(now);
        assert!(history
            .update(Some(&ConnectionStats::default()), now)
            .reading
            .rtt_ms
            .is_none());
        let q = history.measure(42.49, 6.05, now + Duration::from_secs(1));
        assert_eq!(q.reading.rtt_ms, Some(42.49));
        assert_eq!(q.reading.packet_loss_percent, Some(0.0));
        let q = history.measure(0.0, 0.0, now + Duration::from_secs(2));
        assert_eq!(q.reading.rtt_ms, Some(0.0));
        assert_eq!(q.icon_grade, Some(Grade::Good));
    }
    #[test]
    fn recovery_is_immediate_up_five_seconds_down_and_gaps_reset_it() {
        let start = Instant::now();
        let mut history = History::new(start);
        history.measure(250.0, 0.0, start);
        for second in 1..6 {
            assert_eq!(
                history
                    .measure(42.0, 0.0, start + Duration::from_secs(second))
                    .icon_grade,
                Some(Grade::Poor)
            );
        }
        assert_eq!(
            history
                .measure(42.0, 0.0, start + Duration::from_secs(6))
                .icon_grade,
            Some(Grade::Good)
        );
        assert_eq!(
            history
                .measure(42.0, 70.0, start + Duration::from_secs(7))
                .icon_grade,
            Some(Grade::Fair)
        );
        assert_eq!(
            history
                .measure(42.0, 100.0, start + Duration::from_secs(8))
                .icon_grade,
            Some(Grade::Poor)
        );
        for second in 9..14 {
            let q = history.measure(42.0, 70.0, start + Duration::from_secs(second));
            assert_eq!(q.reading.deviation_grade, Some(Grade::Fair));
            assert_eq!(q.icon_grade, Some(Grade::Poor));
            assert_eq!(q.samples.back().unwrap().grade, Grade::Good);
        }
        assert_eq!(
            history
                .measure(42.0, 70.0, start + Duration::from_secs(14))
                .icon_grade,
            Some(Grade::Fair)
        );
        assert!(history
            .expire(start + Duration::from_secs(17))
            .unwrap()
            .icon_grade
            .is_none());
        assert_eq!(
            history
                .update(None, start + Duration::from_secs(18))
                .icon_grade,
            None
        );
    }

    #[test]
    fn mixed_grades_and_recovery_interruptions_are_independent() {
        let r =
            Reading::from_stats(Duration::from_millis(42), Duration::from_millis(6), 0.04).unwrap();
        assert_eq!(r.rtt_grade, Some(Grade::Good));
        assert_eq!(r.deviation_grade, Some(Grade::Good));
        assert_eq!(r.packet_loss_grade, Some(Grade::Poor));
        assert_eq!(
            r.rtt_grade.max(r.deviation_grade).max(r.packet_loss_grade),
            Some(Grade::Poor)
        );
        let start = Instant::now();
        let mut recovery = None;
        assert_eq!(
            buffered(Grade::Poor, Grade::Good, &mut recovery, start),
            Grade::Poor
        );
        assert_eq!(
            buffered(
                Grade::Poor,
                Grade::Fair,
                &mut recovery,
                start + Duration::from_secs(4)
            ),
            Grade::Poor
        );
        assert_eq!(
            buffered(
                Grade::Poor,
                Grade::Good,
                &mut recovery,
                start + Duration::from_secs(8)
            ),
            Grade::Poor
        );
        assert_eq!(
            buffered(
                Grade::Poor,
                Grade::Good,
                &mut recovery,
                start + Duration::from_secs(9)
            ),
            Grade::Fair
        );
        buffered(
            Grade::Fair,
            Grade::Good,
            &mut recovery,
            start + Duration::from_secs(10),
        );
        assert_eq!(
            buffered(
                Grade::Fair,
                Grade::Poor,
                &mut recovery,
                start + Duration::from_secs(11)
            ),
            Grade::Poor
        );
        assert!(recovery.is_none());
    }
    #[test]
    fn timed_window_missing_buckets_axis_headroom_and_shrink() {
        for (peak, expected) in [
            (0.0, 10.0),
            (9.0, 10.0),
            (10.0, 50.0),
            (22.0, 50.0),
            (45.0, 50.0),
            (50.0, 100.0),
            (90.0, 100.0),
            (100.0, 200.0),
            (180.0, 200.0),
            (200.0, 300.0),
            (270.0, 300.0),
            (300.0, 400.0),
            (360.0, 400.0),
            (400.0, 500.0),
            (450.0, 500.0),
            (500.0, 750.0),
            (680.0, 750.0),
            (750.0, 1000.0),
            (900.0, 1000.0),
            (1000.0, 1100.0),
            (1234.0, 1358.0),
            (2000.0, 2200.0),
        ] {
            assert_eq!(axis_limit(peak), expected, "peak: {peak}");
        }
        let start = Instant::now();
        let mut history = History::new(start);
        assert_eq!(history.measure(450.0, 0.0, start).axis_max_ms, 500.0);
        // Duplicate events cannot add another bar; a missing second stays missing.
        history.measure(450.0, 0.0, start + Duration::from_millis(500));
        history.measure(42.0, 0.0, start + Duration::from_secs(2));
        assert_eq!(
            history
                .quality
                .samples
                .iter()
                .map(|s| s.second)
                .collect::<Vec<_>>(),
            [0, 2]
        );
        for second in 3..35 {
            history.measure(42.0, 0.0, start + Duration::from_secs(second));
        }
        assert_eq!(history.quality.samples.len(), 30);
        assert_eq!(history.quality.axis_max_ms, 500.0);
        assert_eq!(
            history
                .measure(42.0, 0.0, start + Duration::from_secs(35))
                .axis_max_ms,
            50.0
        );
        assert_eq!(
            history
                .measure(500.0, 0.0, start + Duration::from_secs(36))
                .axis_max_ms,
            750.0
        );
        for peak in [
            0.0,
            90.0,
            100.0,
            200.0,
            500.0,
            1234.0,
            100_000.0,
            Duration::MAX.as_secs_f64() * 1000.0,
        ] {
            assert!(axis_limit(peak) >= peak * 1.1);
            assert!(axis_limit(peak).is_finite());
        }
        let fresh = History::new(start + Duration::from_secs(100));
        assert!(fresh.quality.samples.is_empty());
        assert_eq!(fresh.quality.axis_max_ms, 10.0);
    }

    #[test]
    fn system_sleep_does_not_preserve_old_history_or_recovery_time() {
        let start = Instant::now();
        let mut history = History::new(start);
        history.measure(450.0, 40.0, start);
        let wall = history.wall_baseline.1;
        // Only 100 ms of monotonic time passed during a minute of system sleep.
        history.advance(
            start + Duration::from_millis(100),
            wall + Duration::from_secs(60),
        );
        assert!(history.quality.samples.is_empty());
        assert!(history.quality.reading.rtt_ms.is_none());
        assert!(history.quality.icon_grade.is_none());
        assert!(history.icon_recovery.is_none());
        assert!(history.axis_recovery.is_none());
        assert_eq!(history.quality.axis_max_ms, 10.0);
        assert_eq!(history.quality.now_second, 60);
        let mut brief = History::new(start);
        brief.measure(42.0, 0.0, start);
        let wall = brief.wall_baseline.1;
        brief.advance(
            start + Duration::from_millis(100),
            wall + Duration::from_secs(3),
        );
        assert_eq!(brief.quality.now_second, 3);
        assert_eq!(brief.quality.samples.len(), 1); // Still a real sample within 30 seconds.
        assert!(brief.quality.icon_grade.is_none());
    }

    #[test]
    fn statistics_ticks_cannot_refresh_rtt_or_duplicate_bars() {
        let start = Instant::now();
        let mut history = History::new(start);
        let mut stats = ConnectionStats::default();
        stats.rtt = Duration::from_millis(33);
        stats.rtt_dev = Duration::from_millis(5);
        stats.rtt_measurements = 1;
        stats.rtt_measured_at = Some(start.into());
        history.update(Some(&stats), start);
        for second in 1..=8 {
            let quality = history.update(Some(&stats), start + Duration::from_secs(second));
            assert_eq!(quality.samples.len(), 1);
            assert_eq!(quality.reading.rtt_ms, (second <= 5).then_some(33.0));
            assert_eq!(quality.reading.packet_loss_percent, Some(0.0));
        }
        // Identical values with a new accepted measurement are still fresh.
        stats.rtt_measurements = 2;
        stats.rtt_measured_at = Some((start + Duration::from_secs(8)).into());
        let quality = history.update(Some(&stats), start + Duration::from_secs(9));
        assert_eq!(quality.reading.rtt_ms, Some(33.0));
        assert_eq!(
            quality
                .samples
                .iter()
                .map(|sample| sample.second)
                .collect::<Vec<_>>(),
            [0, 8]
        );
        history.update(Some(&stats), start + Duration::from_secs(13));
        assert!(history
            .expire(start + Duration::from_secs(14))
            .unwrap()
            .reading
            .rtt_ms
            .is_none());
    }

    #[test]
    fn waking_cannot_republish_a_pre_sleep_measurement() {
        let start = Instant::now();
        let mut history = History::new(start);
        let mut stats = ConnectionStats::default();
        stats.rtt_measurements = 1;
        stats.rtt_measured_at = Some(start.into());
        history.update(Some(&stats), start);
        let wall = history.wall_baseline.1;
        let now = start + Duration::from_millis(100);
        history.advance(now, wall + Duration::from_secs(60));
        assert!(history.update(Some(&stats), now).reading.rtt_ms.is_none());
        assert!(history.quality.samples.is_empty());
        stats.rtt_measurements = 2;
        stats.rtt_measured_at = Some(now.into());
        assert_eq!(history.update(Some(&stats), now).reading.rtt_ms, Some(0.0));
        assert_eq!(history.quality.samples.len(), 1);
    }
}
