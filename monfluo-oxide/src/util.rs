use std::{
    borrow::Cow,
    fmt::{Debug, Display, Write},
    ops::{Deref, Div},
};

use bigdecimal::{BigDecimal, FromPrimitive};
use monero_wallet::{address::SubaddressIndex, ed25519::Scalar};
use zeroize::Zeroizing;

use crate::MoneroBytes;

pub struct HexFormatted<'slice>(pub Cow<'slice, [u8]>);

impl<'slice> Display for HexFormatted<'slice> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        const MASK: u8 = 0x0f;

        let mut print_4_bits = |bits: u8| match bits {
            0..=9 => f.write_char((b'0' + bits) as char),
            10..=15 => f.write_char((b'a' + bits - 10) as char),
            _ => unreachable!(),
        };

        for &byte in self.0.deref() {
            let high = (byte >> 4) & MASK;
            let low = byte & MASK;
            print_4_bits(high)?;
            print_4_bits(low)?;
        }

        Ok(())
    }
}

impl<'slice> Debug for HexFormatted<'slice> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("HexFormatted")
            .field(&self.to_string())
            .finish()
    }
}

impl<'slice> HexFormatted<'slice> {
    pub fn borrowed(bytes: &'slice [u8]) -> Self {
        Self(Cow::Borrowed(bytes))
    }
}

pub struct SubaddressIndexDisplay(pub Option<SubaddressIndex>);

impl Display for SubaddressIndexDisplay {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let (account, address) = match self.0 {
            Some(subaddress_index) => (subaddress_index.account(), subaddress_index.address()),
            None => (0, 0),
        };

        write!(f, "{}", account)?;
        f.write_char('/')?;
        write!(f, "{}", address)?;

        Ok(())
    }
}

pub fn bytes_to_scalar(bytes: &MoneroBytes) -> Option<Zeroizing<Scalar>> {
    curve25519_dalek::Scalar::from_canonical_bytes(*bytes)
        .into_option()
        .map(Scalar::from)
        .map(Zeroizing::new)
}

pub fn hex_to_bytes(hex: &str) -> Option<Zeroizing<Vec<u8>>> {
    if !hex.len().is_multiple_of(2) {
        return None;
    }

    let mut result = Vec::with_capacity(hex.len() / 2);

    for i in (0..hex.len()).step_by(2) {
        let byte = u8::from_str_radix(&hex[i..i + 2], 16).ok()?;
        result.push(byte);
    }

    Some(Zeroizing::new(result))
}

pub fn atomic_units_to_big_decimal(atomic_units: u64) -> BigDecimal {
    BigDecimal::from_u64(atomic_units)
        .expect("Failed to convert atomic units to big decimal")
        .div(10u64.pow(12))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    #[test]
    fn test_hex_roundtrip_32_bytes() {
        let original: Vec<u8> = (0..32).collect();
        let hex_string = HexFormatted::borrowed(&original).to_string();
        let recovered = hex_to_bytes(&hex_string).unwrap();
        assert_eq!(original.as_slice(), recovered.as_slice());
    }

    #[test]
    fn test_hex_roundtrip_random_32_bytes() {
        let original: Vec<u8> = vec![0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f, 0x70, 0x81, 0x92, 0xa3, 0xb4, 0xc5, 0xd6, 0xe7, 0xf8, 0x09, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x00];
        let hex_string = HexFormatted::borrowed(&original).to_string();
        let recovered = hex_to_bytes(&hex_string).unwrap();
        assert_eq!(original.as_slice(), recovered.as_slice());
    }

    #[test]
    fn test_hex_to_bytes_invalid_odd_length() {
        assert!(hex_to_bytes("123").is_none());
    }

    #[test]
    fn test_hex_to_bytes_invalid_chars() {
        assert!(hex_to_bytes("1zz").is_none());
    }

    #[test]
    fn test_atomic_units_to_big_decimal() {
        const TEST_CASES: &[(u64, &'static str)] = &[(1, "0.000000000001"), (1_000, "0.000000001000"), (1_000_000, "0.000001000000"), (1_000_000_000, "0.001000000000"), (1_000_000_000_000, "1.000000000000"), (12_345_678_901_234, "12.345678901234")];

        for (atomic_units, exptected_str) in TEST_CASES {
            let result = atomic_units_to_big_decimal(*atomic_units);
            let expected = BigDecimal::from_str(exptected_str).unwrap();
            assert_eq!(result, expected);
        }
    }
}
