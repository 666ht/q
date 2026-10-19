// everything in this module should ideally be upstreamed either to monero-oxide or monero-wallet-util

use std::ops::Deref;

use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_wallet::{
    WalletOutput,
    ed25519::{CompressedPoint, Point, Scalar},
};
use zeroize::Zeroizing;

use crate::MoneroBytes;

fn sc_reduce32(s: &mut MoneroBytes) {
    let mut s0: i64 = 2097151 & load_3(&s[0..]) as i64;
    let mut s1: i64 = 2097151 & (load_4(&s[2..]) >> 5) as i64;
    let mut s2: i64 = 2097151 & (load_3(&s[5..]) >> 2) as i64;
    let mut s3: i64 = 2097151 & (load_4(&s[7..]) >> 7) as i64;
    let mut s4: i64 = 2097151 & (load_4(&s[10..]) >> 4) as i64;
    let mut s5: i64 = 2097151 & (load_3(&s[13..]) >> 1) as i64;
    let mut s6: i64 = 2097151 & (load_4(&s[15..]) >> 6) as i64;
    let mut s7: i64 = 2097151 & (load_3(&s[18..]) >> 3) as i64;
    let mut s8: i64 = 2097151 & load_3(&s[21..]) as i64;
    let mut s9: i64 = 2097151 & (load_4(&s[23..]) >> 5) as i64;
    let mut s10: i64 = 2097151 & (load_3(&s[26..]) >> 2) as i64;
    let mut s11: i64 = (load_4(&s[28..]) >> 7) as i64;
    let mut s12: i64 = 0;
    let mut carry0: i64;
    let mut carry1: i64;
    let mut carry2: i64;
    let mut carry3: i64;
    let mut carry4: i64;
    let mut carry5: i64;
    let mut carry6: i64;
    let mut carry7: i64;
    let mut carry8: i64;
    let mut carry9: i64;
    let mut carry10: i64;
    let mut carry11: i64;

    carry0 = (s0 + (1 << 20)) >> 21;
    s1 += carry0;
    s0 -= carry0 << 21;
    carry2 = (s2 + (1 << 20)) >> 21;
    s3 += carry2;
    s2 -= carry2 << 21;
    carry4 = (s4 + (1 << 20)) >> 21;
    s5 += carry4;
    s4 -= carry4 << 21;
    carry6 = (s6 + (1 << 20)) >> 21;
    s7 += carry6;
    s6 -= carry6 << 21;
    carry8 = (s8 + (1 << 20)) >> 21;
    s9 += carry8;
    s8 -= carry8 << 21;
    carry10 = (s10 + (1 << 20)) >> 21;
    s11 += carry10;
    s10 -= carry10 << 21;

    carry1 = (s1 + (1 << 20)) >> 21;
    s2 += carry1;
    s1 -= carry1 << 21;
    carry3 = (s3 + (1 << 20)) >> 21;
    s4 += carry3;
    s3 -= carry3 << 21;
    carry5 = (s5 + (1 << 20)) >> 21;
    s6 += carry5;
    s5 -= carry5 << 21;
    carry7 = (s7 + (1 << 20)) >> 21;
    s8 += carry7;
    s7 -= carry7 << 21;
    carry9 = (s9 + (1 << 20)) >> 21;
    s10 += carry9;
    s9 -= carry9 << 21;
    carry11 = (s11 + (1 << 20)) >> 21;
    s12 += carry11;
    s11 -= carry11 << 21;

    s0 += s12 * 666643;
    s1 += s12 * 470296;
    s2 += s12 * 654183;
    s3 -= s12 * 997805;
    s4 += s12 * 136657;
    s5 -= s12 * 683901;
    s12 = 0;

    carry0 = s0 >> 21;
    s1 += carry0;
    s0 -= carry0 << 21;
    carry1 = s1 >> 21;
    s2 += carry1;
    s1 -= carry1 << 21;
    carry2 = s2 >> 21;
    s3 += carry2;
    s2 -= carry2 << 21;
    carry3 = s3 >> 21;
    s4 += carry3;
    s3 -= carry3 << 21;
    carry4 = s4 >> 21;
    s5 += carry4;
    s4 -= carry4 << 21;
    carry5 = s5 >> 21;
    s6 += carry5;
    s5 -= carry5 << 21;
    carry6 = s6 >> 21;
    s7 += carry6;
    s6 -= carry6 << 21;
    carry7 = s7 >> 21;
    s8 += carry7;
    s7 -= carry7 << 21;
    carry8 = s8 >> 21;
    s9 += carry8;
    s8 -= carry8 << 21;
    carry9 = s9 >> 21;
    s10 += carry9;
    s9 -= carry9 << 21;
    carry10 = s10 >> 21;
    s11 += carry10;
    s10 -= carry10 << 21;
    carry11 = s11 >> 21;
    s12 += carry11;
    s11 -= carry11 << 21;

    s0 += s12 * 666643;
    s1 += s12 * 470296;
    s2 += s12 * 654183;
    s3 -= s12 * 997805;
    s4 += s12 * 136657;
    s5 -= s12 * 683901;

    carry0 = s0 >> 21;
    s1 += carry0;
    s0 -= carry0 << 21;
    carry1 = s1 >> 21;
    s2 += carry1;
    s1 -= carry1 << 21;
    carry2 = s2 >> 21;
    s3 += carry2;
    s2 -= carry2 << 21;
    carry3 = s3 >> 21;
    s4 += carry3;
    s3 -= carry3 << 21;
    carry4 = s4 >> 21;
    s5 += carry4;
    s4 -= carry4 << 21;
    carry5 = s5 >> 21;
    s6 += carry5;
    s5 -= carry5 << 21;
    carry6 = s6 >> 21;
    s7 += carry6;
    s6 -= carry6 << 21;
    carry7 = s7 >> 21;
    s8 += carry7;
    s7 -= carry7 << 21;
    carry8 = s8 >> 21;
    s9 += carry8;
    s8 -= carry8 << 21;
    carry9 = s9 >> 21;
    s10 += carry9;
    s9 -= carry9 << 21;
    carry10 = s10 >> 21;
    s11 += carry10;
    s10 -= carry10 << 21;

    s[0] = (s0 & 0xFF) as u8;
    s[1] = (s0 >> 8 & 0xFF) as u8;
    s[2] = ((s0 >> 16) | (s1 << 5)) as u8;
    s[3] = (s1 >> 3 & 0xFF) as u8;
    s[4] = (s1 >> 11 & 0xFF) as u8;
    s[5] = ((s1 >> 19) | (s2 << 2)) as u8;
    s[6] = (s2 >> 6 & 0xFF) as u8;
    s[7] = ((s2 >> 14) | (s3 << 7)) as u8;
    s[8] = (s3 >> 1 & 0xFF) as u8;
    s[9] = (s3 >> 9 & 0xFF) as u8;
    s[10] = ((s3 >> 17) | (s4 << 4)) as u8;
    s[11] = (s4 >> 4 & 0xFF) as u8;
    s[12] = (s4 >> 12 & 0xFF) as u8;
    s[13] = ((s4 >> 20) | (s5 << 1)) as u8;
    s[14] = (s5 >> 7 & 0xFF) as u8;
    s[15] = ((s5 >> 15) | (s6 << 6)) as u8;
    s[16] = (s6 >> 2 & 0xFF) as u8;
    s[17] = (s6 >> 10 & 0xFF) as u8;
    s[18] = ((s6 >> 18) | (s7 << 3)) as u8;
    s[19] = (s7 >> 5 & 0xFF) as u8;
    s[20] = (s7 >> 13 & 0xFF) as u8;
    s[21] = (s8 & 0xFF) as u8;
    s[22] = (s8 >> 8 & 0xFF) as u8;
    s[23] = ((s8 >> 16) | (s9 << 5)) as u8;
    s[24] = (s9 >> 3 & 0xFF) as u8;
    s[25] = (s9 >> 11 & 0xFF) as u8;
    s[26] = ((s9 >> 19) | (s10 << 2)) as u8;
    s[27] = (s10 >> 6 & 0xFF) as u8;
    s[28] = ((s10 >> 14) | (s11 << 7)) as u8;
    s[29] = (s11 >> 1 & 0xFF) as u8;
    s[30] = (s11 >> 9 & 0xFF) as u8;
    s[31] = (s11 >> 17 & 0xFF) as u8;
}

fn load_3(in_bytes: &[u8]) -> u64 {
    let mut result: u64 = in_bytes[0] as u64;
    result |= (in_bytes[1] as u64) << 8;
    result |= (in_bytes[2] as u64) << 16;
    result
}

fn load_4(in_bytes: &[u8]) -> u64 {
    let mut result: u64 = in_bytes[0] as u64;
    result |= (in_bytes[1] as u64) << 8;
    result |= (in_bytes[2] as u64) << 16;
    result |= (in_bytes[3] as u64) << 24;
    result
}

pub fn spend_key_to_view_key(spend: &MoneroBytes) -> Zeroizing<MoneroBytes> {
    let mut key2keccaked = monero_wallet::primitives::keccak256(spend);
    sc_reduce32(&mut key2keccaked);
    Zeroizing::new(key2keccaked)
}

pub fn key_image_of_output(output: &WalletOutput, sender_spend_key: &Scalar) -> CompressedPoint {
    key_image_of_output_inner(output, sender_spend_key).expect("Failed to compute key image of an output")
}

fn key_image_of_output_inner(output: &WalletOutput, sender_spend_key: &Scalar) -> Option<CompressedPoint> {
    let input_key = Zeroizing::new((*sender_spend_key).into() + output.key_offset().into());
    if !(input_key.deref() * ED25519_BASEPOINT_TABLE).eq(&output.key().into()) {
        return None;
    }
    let key_image = Point::from(input_key.deref() * Point::biased_hash(output.key().compress().to_bytes()).into());
    Some(key_image.compress())
}
