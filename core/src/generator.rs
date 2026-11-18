//! The password generator.
//!
//! Passwords are drawn uniformly from every password of the chosen length that uses only the
//! chosen characters and contains at least one of each chosen kind (lowercase, uppercase,
//! digits, symbols). Sites often require one of each, and drawing uniformly from just those
//! passwords (rather than placing one of each kind and shuffling, which favors some passwords
//! over others) means the strength we report is exact.
//!
//! Randomness comes from the operating system ([`getrandom`]), and every character is picked
//! without bias by rejection sampling.

use zeroize::Zeroizing;

use crate::error::{Error, Result};
use crate::util::{SecretBuf, random_bytes};

const LOWERCASE: &str = "abcdefghijklmnopqrstuvwxyz";
const UPPERCASE: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGITS: &str = "0123456789";

/// Symbols that sites commonly accept. Sites that limit symbols tend to reject quotes, `<>&`
/// (HTML), `\`, `;`, spaces, and brackets, and some don't count `_` as a symbol at all (a
/// `\W` check). What's left is the run above the number keys, which is what most "allowed
/// special characters" lists name, plus `-`.
pub const COMMON_SYMBOLS: &str = "!@#$%^*-";

/// Every printable ASCII symbol (no space).
pub const ALL_SYMBOLS: &str = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

/// Characters that are easy to mistake for one another when reading a password aloud or
/// typing it from another screen.
pub const LOOKALIKES: &str = "0Oo1Il|";

/// The shortest and longest passwords the generator makes.
pub const MIN_LENGTH: u32 = 4;
pub const MAX_LENGTH: u32 = 256;

/// Which symbols a password may contain.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Symbols {
    None,
    /// [`COMMON_SYMBOLS`]: the short set that sites commonly accept.
    Common,
    /// [`ALL_SYMBOLS`].
    All,
}

/// What kind of password to make.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PasswordOptions {
    pub length: u32,
    pub lowercase: bool,
    pub uppercase: bool,
    pub digits: bool,
    pub symbols: Symbols,
    /// Leave out [`LOOKALIKES`].
    pub avoid_lookalikes: bool,
}

impl Default for PasswordOptions {
    fn default() -> Self {
        Self {
            length: 20,
            lowercase: true,
            uppercase: true,
            digits: true,
            symbols: Symbols::Common,
            avoid_lookalikes: false,
        }
    }
}

impl PasswordOptions {
    /// The character sets in use, each with the look-alikes taken out if asked. A password has
    /// at least one character from each.
    fn sets(&self) -> Result<Vec<Vec<u8>>> {
        let symbols = match self.symbols {
            Symbols::None => None,
            Symbols::Common => Some(COMMON_SYMBOLS),
            Symbols::All => Some(ALL_SYMBOLS),
        };
        let chosen = [
            self.lowercase.then_some(LOWERCASE),
            self.uppercase.then_some(UPPERCASE),
            self.digits.then_some(DIGITS),
            symbols,
        ];
        let sets: Vec<Vec<u8>> = chosen
            .into_iter()
            .flatten()
            .map(|set| {
                set.bytes()
                    .filter(|b| !(self.avoid_lookalikes && LOOKALIKES.as_bytes().contains(b)))
                    .collect()
            })
            .collect();
        if sets.is_empty() {
            return Err(Error::Invalid(
                "Choose at least one kind of character.".into(),
            ));
        }
        if !(MIN_LENGTH..=MAX_LENGTH).contains(&self.length) {
            return Err(Error::Invalid(format!(
                "A generated password has {MIN_LENGTH} to {MAX_LENGTH} characters."
            )));
        }
        Ok(sets)
    }

    /// How many bits of randomness a password made with these options has: the base-2
    /// logarithm of how many passwords it's drawn from.
    pub fn strength_bits(&self) -> Result<f64> {
        let sets = self.sets()?;
        Ok(log2_count(&sets, self.length))
    }

    /// Makes a password. It's ASCII, so its bytes are also its UTF-8.
    pub fn generate(&self) -> Result<Zeroizing<Vec<u8>>> {
        let sets = self.sets()?;
        let pool: Vec<u8> = sets.concat();
        let length = self.length as usize;
        let mut random = RandomIndex::new();
        // Rejection: draw from the whole pool until a password has every kind. Even in the
        // worst case (one character per kind, so every one must differ) about one draw in 20
        // succeeds, so this ends quickly.
        loop {
            let mut password = SecretBuf::with_capacity(length);
            for _ in 0..length {
                let i = random.below(pool.len())?;
                password.extend(&pool[i..=i]);
            }
            let password = password.into_inner();
            if sets
                .iter()
                .all(|set| password.iter().any(|c| set.contains(c)))
            {
                return Ok(password);
            }
        }
    }
}

/// log2 of the number of `length`-character strings over the union of `sets` that use every
/// set at least once, by inclusion-exclusion over the sets left out. The sets don't overlap.
fn log2_count(sets: &[Vec<u8>], length: u32) -> f64 {
    let total: usize = sets.iter().map(Vec::len).sum();
    let n = sets.len();
    // Each term is (size of the pool without some sets / total)^length, so they stay at most 1
    // and the sum doesn't overflow for long passwords.
    let mut fraction = 0.0;
    for missing in 0u32..(1 << n) {
        let left_out: usize = (0..n)
            .filter(|i| missing & (1 << i) != 0)
            .map(|i| sets[i].len())
            .sum();
        let term = ((total - left_out) as f64 / total as f64).powi(length as i32);
        if missing.count_ones() % 2 == 0 {
            fraction += term;
        } else {
            fraction -= term;
        }
    }
    length as f64 * (total as f64).log2() + fraction.log2()
}

/// Unbiased random indexes, from the operating system's random number generator, fetched in
/// batches.
struct RandomIndex {
    bytes: Zeroizing<Vec<u8>>,
    used: usize,
}

impl RandomIndex {
    const BATCH: usize = 256;

    fn new() -> Self {
        Self {
            bytes: Zeroizing::new(Vec::new()),
            used: 0,
        }
    }

    fn next_u32(&mut self) -> Result<u32> {
        if self.used + 4 > self.bytes.len() {
            self.bytes = random_bytes(Self::BATCH)?;
            self.used = 0;
        }
        let word = u32::from_le_bytes(self.bytes[self.used..self.used + 4].try_into().unwrap());
        self.used += 4;
        Ok(word)
    }

    /// A number in `0..n`, with every one equally likely: draws that fall in the incomplete
    /// last stretch of the u32 range are thrown away instead of wrapped around.
    fn below(&mut self, n: usize) -> Result<usize> {
        let n = n as u32;
        let limit = u32::MAX - u32::MAX % n;
        loop {
            let word = self.next_u32()?;
            if word < limit {
                return Ok((word % n) as usize);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn options(length: u32) -> PasswordOptions {
        PasswordOptions {
            length,
            ..PasswordOptions::default()
        }
    }

    #[test]
    fn a_password_has_the_length_and_only_the_chosen_characters() {
        for symbols in [Symbols::None, Symbols::Common, Symbols::All] {
            let opts = PasswordOptions {
                symbols,
                ..options(64)
            };
            let allowed: Vec<u8> = opts.sets().unwrap().concat();
            for _ in 0..50 {
                let password = opts.generate().unwrap();
                assert_eq!(password.len(), 64);
                assert!(password.iter().all(|c| allowed.contains(c)));
            }
        }
    }

    #[test]
    fn every_chosen_kind_appears_even_in_the_shortest_password() {
        let opts = PasswordOptions {
            symbols: Symbols::All,
            ..options(MIN_LENGTH)
        };
        for _ in 0..200 {
            let password = opts.generate().unwrap();
            assert!(password.iter().any(u8::is_ascii_lowercase));
            assert!(password.iter().any(u8::is_ascii_uppercase));
            assert!(password.iter().any(u8::is_ascii_digit));
            assert!(password.iter().any(|c| ALL_SYMBOLS.as_bytes().contains(c)));
        }
    }

    #[test]
    fn common_symbols_are_a_subset_of_all_symbols() {
        assert_eq!(ALL_SYMBOLS.len(), 32);
        assert!(
            COMMON_SYMBOLS
                .bytes()
                .all(|c| ALL_SYMBOLS.as_bytes().contains(&c))
        );
        // Each one must count as a symbol for a `\W` check.
        assert!(
            COMMON_SYMBOLS
                .bytes()
                .all(|c| c.is_ascii_punctuation() && c != b'_')
        );
    }

    #[test]
    fn lookalikes_can_be_left_out() {
        let opts = PasswordOptions {
            avoid_lookalikes: true,
            symbols: Symbols::All,
            ..options(256)
        };
        for _ in 0..20 {
            let password = opts.generate().unwrap();
            assert!(!password.iter().any(|c| LOOKALIKES.as_bytes().contains(c)));
        }
    }

    #[test]
    fn a_single_kind_works() {
        let opts = PasswordOptions {
            lowercase: false,
            uppercase: false,
            symbols: Symbols::None,
            ..options(12)
        };
        let password = opts.generate().unwrap();
        assert!(password.iter().all(u8::is_ascii_digit));
        // 10^12 PINs, every one possible.
        assert!((opts.strength_bits().unwrap() - 12.0 * 10f64.log2()).abs() < 1e-9);
    }

    #[test]
    fn options_that_make_no_password_are_refused() {
        let none = PasswordOptions {
            lowercase: false,
            uppercase: false,
            digits: false,
            symbols: Symbols::None,
            ..options(20)
        };
        assert!(matches!(none.generate(), Err(Error::Invalid(_))));
        assert!(matches!(none.strength_bits(), Err(Error::Invalid(_))));
        assert!(matches!(
            options(MIN_LENGTH - 1).generate(),
            Err(Error::Invalid(_))
        ));
        assert!(matches!(
            options(MAX_LENGTH + 1).generate(),
            Err(Error::Invalid(_))
        ));
    }

    #[test]
    fn strength_counts_only_passwords_with_every_kind() {
        // Two kinds, "ab" and "c", length 2: of the 9 strings, ac, bc, ca, cb have both.
        let sets = vec![b"ab".to_vec(), b"c".to_vec()];
        assert!((log2_count(&sets, 2) - 4f64.log2()).abs() < 1e-9);
        // Length 3: 27 strings, minus 8 without "c", minus 1 without "a" or "b" = 18.
        assert!((log2_count(&sets, 3) - 18f64.log2()).abs() < 1e-9);
    }

    #[test]
    fn strength_of_the_default_password() {
        // 26 + 26 + 10 + 8 = 70 characters, 20 long: just under 20 * log2(70) = 122.6 bits.
        let bits = PasswordOptions::default().strength_bits().unwrap();
        assert!(bits < 20.0 * 70f64.log2() && bits > 122.0, "{bits}");
        // Long passwords don't overflow.
        let bits = options(MAX_LENGTH).strength_bits().unwrap();
        assert!((bits - 256.0 * 70f64.log2()).abs() < 0.01, "{bits}");
    }

    #[test]
    fn random_indexes_cover_the_range_evenly() {
        let mut random = RandomIndex::new();
        let mut counts = [0u32; 7];
        for _ in 0..70_000 {
            counts[random.below(7).unwrap()] += 1;
        }
        // Expected 10,000 each; 5 standard deviations is about ±460.
        assert!(
            counts.iter().all(|&c| (9_500..10_500).contains(&c)),
            "{counts:?}"
        );
    }
}
