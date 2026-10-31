/*
 * Copyright 2018 m2049r
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anonero.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;

import timber.log.Timber;


public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";

    static {
        System.loadLibrary("anonero");
    }

    public static native byte[] slowHash(byte[] data, int brokenVariant);

    static final private String RSA_ALIAS = "MonerujoRSA";
    private static final String WALLET_SECRET_PREFIX = "walletSecret-";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private static String getCrazyPass(Context context, String password, int brokenVariant) {
        byte[] data = password.getBytes(StandardCharsets.UTF_8);
        byte[] sig = null;
        try {
            KeyStoreHelper.createKeys(context, RSA_ALIAS);
            sig = KeyStoreHelper.signData(data);
            byte[] hash = slowHash(sig, brokenVariant);
            if (hash == null) {
                throw new IllegalStateException("Slow Hash is null!");
            }
            return CrazyPassEncoder.encode(hash);
        } catch (NoSuchProviderException | NoSuchAlgorithmException |
                 InvalidAlgorithmParameterException | KeyStoreException |
                 InvalidKeyException | SignatureException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String getCrazyPass(Context context, String password) {
        return getCrazyPass(context, password, 0);

    }

    public static String getBrokenCrazyPass(Context context, String password, int brokenVariant) {
        // due to a link bug in the initial implementation, some crazypasses were built with
        // prehash & variant == 1
        // since there are wallets out there, we need to keep this here
        // yes, it's a mess
        if (isArm32() && (brokenVariant != 2)) return null;
        return getCrazyPass(context, password, brokenVariant);
    }

    private static Boolean isArm32 = null;

    public static boolean isArm32() {
        if (isArm32 != null) return isArm32;
        synchronized (KeyStoreException.class) {
            if (isArm32 != null) return isArm32;
            isArm32 = Build.SUPPORTED_ABIS[0].equals("armeabi-v7a");
            return isArm32;
        }
    }

    public static void saveWalletUserPass(@NonNull Context context, String wallet, String password) {
        String walletKeyAlias = SecurityConstants.WALLET_PASS_KEY_PREFIX + wallet;
        byte[] data = password.getBytes(StandardCharsets.UTF_8);
        try {
            KeyStoreHelper.createKeys(context, walletKeyAlias);
            byte[] encrypted = KeyStoreHelper.encrypt(walletKeyAlias, data);
            SharedPreferences.Editor e = context.getSharedPreferences(SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE).edit();
            if (encrypted == null) {
                e.remove(wallet).apply();
                return;
            }
            e.putString(wallet, Base64.encodeToString(encrypted, Base64.DEFAULT)).apply();
        } catch (NoSuchProviderException | NoSuchAlgorithmException |
                 InvalidAlgorithmParameterException | KeyStoreException ex) {
            Timber.tag(TAG).e(ex);
        }
    }

    static public class BrokenPasswordStoreException extends Exception {
        BrokenPasswordStoreException() {
            super();
        }

        BrokenPasswordStoreException(Throwable cause) {
            super(cause);
        }
    }

    public static boolean hasStoredPasswords(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getAll().isEmpty();
    }

    public static String loadWalletUserPass(@NonNull Context context, String wallet) throws BrokenPasswordStoreException {
        String walletKeyAlias = SecurityConstants.WALLET_PASS_KEY_PREFIX + wallet;
        String encoded = context.getSharedPreferences(SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(wallet, "");
        if (encoded.isEmpty()) throw new BrokenPasswordStoreException();
        byte[] data = Base64.decode(encoded, Base64.DEFAULT);
        byte[] decrypted = KeyStoreHelper.decrypt(walletKeyAlias, data);
        if (decrypted == null) throw new BrokenPasswordStoreException();
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public static void removeWalletUserPass(Context context, String wallet) {
        String walletKeyAlias = SecurityConstants.WALLET_PASS_KEY_PREFIX + wallet;
        try {
            KeyStoreHelper.deleteKeys(walletKeyAlias);
        } catch (KeyStoreException ex) {
            Timber.tag(TAG).e(ex);
        }
        context.getSharedPreferences(SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(wallet).apply();
    }


    // --- Random wallet password wrapped by a per-wallet Android Keystore AES key ---

    private static SecretKey generateWalletSecretKey(String alias, boolean strongBox)
            throws GeneralSecurityException {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (strongBox) builder.setIsStrongBoxBacked(true);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        generator.init(builder.build());
        return generator.generateKey();
    }

    private static SecretKey getOrCreateWalletSecretKey(String alias)
            throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
        if (keyStore.containsAlias(alias)) {
            java.security.Key existing = keyStore.getKey(alias, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            throw new GeneralSecurityException("Wallet secret alias is not an AES secret key");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateWalletSecretKey(alias, true);
            } catch (Exception strongBoxUnavailable) {
                Timber.tag(TAG).i("StrongBox unavailable for wallet secret; falling back to device Keystore");
                try {
                    keyStore.deleteEntry(alias);
                } catch (Exception ignored) {
                    // A subsequent generation attempt reports the definitive failure.
                }
            }
        }
        return generateWalletSecretKey(alias, false);
    }

    public static void saveWalletSecret(Context context, String wallet, String secret)
            throws GeneralSecurityException {
        String alias = WALLET_SECRET_PREFIX + wallet;
        SecretKey key = getOrCreateWalletSecretKey(alias);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new GeneralSecurityException("Android Keystore returned an invalid AES-GCM IV");
        }
        byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] wrapped = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, wrapped, 0, iv.length);
        System.arraycopy(ciphertext, 0, wrapped, iv.length, ciphertext.length);

        boolean saved = context.getSharedPreferences(
                SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(WALLET_SECRET_PREFIX + wallet, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .commit();
        if (!saved) throw new GeneralSecurityException("Wrapped wallet secret was not committed");
    }

    public static String loadWalletSecret(Context context, String wallet)
            throws GeneralSecurityException {
        String encoded = context.getSharedPreferences(
                SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(WALLET_SECRET_PREFIX + wallet, "");
        if (encoded == null || encoded.isEmpty()) {
            throw new GeneralSecurityException("No wrapped wallet secret is stored");
        }
        byte[] wrapped;
        try {
            wrapped = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Wrapped wallet secret is malformed", e);
        }
        if (wrapped.length < GCM_IV_BYTES + 16) {
            throw new GeneralSecurityException("Wrapped wallet secret is truncated");
        }

        byte[] iv = Arrays.copyOfRange(wrapped, 0, GCM_IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(wrapped, GCM_IV_BYTES, wrapped.length);
        KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
        java.security.Key storedKey = keyStore.getKey(WALLET_SECRET_PREFIX + wallet, null);
        if (!(storedKey instanceof SecretKey)) {
            throw new GeneralSecurityException("Wallet Keystore key is missing");
        }
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, (SecretKey) storedKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public static boolean walletSecretExists(Context context, String wallet) {
        String encoded = context.getSharedPreferences(
                SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(WALLET_SECRET_PREFIX + wallet, "");
        return encoded != null && !encoded.isEmpty();
    }

    public static void removeWalletSecret(Context context, String wallet) {
        try {
            KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(WALLET_SECRET_PREFIX + wallet);
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "Unable to remove wallet Keystore key");
        }
        context.getSharedPreferences(
                SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(WALLET_SECRET_PREFIX + wallet).commit();
    }

    public static void copyWalletUserPass(Context context, String srcWallet, String dstWallet) throws BrokenPasswordStoreException {
        final String pass = loadWalletUserPass(context, srcWallet);
        saveWalletUserPass(context, dstWallet, pass);
    }

    /**
     * Creates a public and private key and stores it using the Android Key
     * Store, so that only this application will be able to access the keys.
     */
    private static void createKeys(Context context, String alias) throws NoSuchProviderException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException | CertificateException ex) {
            throw new IllegalStateException("Could not load KeyStore", ex);
        }
        if (!keyStore.containsAlias(alias)) {
            createKeysM(alias);
        }
    }

    private static KeyStore loadAndroidKeyStore() throws KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException | NoSuchAlgorithmException | CertificateException ex) {
            throw new KeyStoreException("Unable to load Android Keystore", ex);
        }
        return keyStore;
    }

    private static void deleteKeys(String alias) throws KeyStoreException {
        KeyStore keyStore = loadAndroidKeyStore();
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias);
        }
        // A successful deleteEntry call is not enough: verify that Android Keystore
        // no longer exposes the alias before reporting the cleanup as complete.
        if (keyStore.containsAlias(alias)) {
            throw new KeyStoreException("Android Keystore alias remains after deletion: " + alias);
        }
    }

    public static void clearWalletCredentials(@NonNull Context context, String wallet) {
        SharedPreferences passwordPreferences = context.getSharedPreferences(
                SecurityConstants.WALLET_PASS_PREFS_NAME, Context.MODE_PRIVATE);
        String walletKeyAlias = SecurityConstants.WALLET_PASS_KEY_PREFIX + wallet;
        String walletSecretAlias = WALLET_SECRET_PREFIX + wallet;
        List<String> aliasesToDelete = new ArrayList<>();

        // Discover all wallet password keys, including keys left behind by older
        // wallet names. Only remove aliases owned by this helper: walletKey-* and
        // the shared MonerujoRSA password-protection key.
        try {
            KeyStore keyStore = loadAndroidKeyStore();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (RSA_ALIAS.equals(alias) ||
                        alias.startsWith(SecurityConstants.WALLET_PASS_KEY_PREFIX) ||
                        alias.startsWith(WALLET_SECRET_PREFIX)) {
                    aliasesToDelete.add(alias);
                }
            }
        } catch (KeyStoreException ex) {
            throw new IllegalStateException("Unable to enumerate Android Keystore wallet keys", ex);
        }

        if (!aliasesToDelete.contains(walletKeyAlias)) aliasesToDelete.add(walletKeyAlias);
        if (!aliasesToDelete.contains(walletSecretAlias)) aliasesToDelete.add(walletSecretAlias);
        if (!aliasesToDelete.contains(RSA_ALIAS)) aliasesToDelete.add(RSA_ALIAS);
        for (String preferenceName : passwordPreferences.getAll().keySet()) {
            if (preferenceName.startsWith(SecurityConstants.WALLET_PASS_KEY_PREFIX) ||
                    preferenceName.startsWith(WALLET_SECRET_PREFIX)) {
                if (!aliasesToDelete.contains(preferenceName)) aliasesToDelete.add(preferenceName);
            } else {
                String alias = SecurityConstants.WALLET_PASS_KEY_PREFIX + preferenceName;
                if (!aliasesToDelete.contains(alias)) aliasesToDelete.add(alias);
            }
        }

        // Attempt every key independently. One failed alias must not prevent
        // other wallet keys from being deleted; a later verification decides
        // whether the secure-delete flow may proceed.
        for (String alias : aliasesToDelete) {
            try {
                deleteKeys(alias);
            } catch (KeyStoreException ex) {
                Timber.tag(TAG).e(ex, "Failed to delete Android Keystore wallet key");
            }
        }

        // Enumerate again after the deletion attempts. If any wallet-related
        // alias remains, fail closed so SecureWipe retries instead of reporting
        // success or deleting the alias inventory needed for another attempt.
        try {
            KeyStore keyStore = loadAndroidKeyStore();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (RSA_ALIAS.equals(alias) ||
                        alias.startsWith(SecurityConstants.WALLET_PASS_KEY_PREFIX) ||
                        alias.startsWith(WALLET_SECRET_PREFIX)) {
                    throw new IllegalStateException(
                            "Android Keystore wallet key remains after secure deletion");
                }
            }
            if (keyStore.containsAlias(walletKeyAlias) ||
                    keyStore.containsAlias(walletSecretAlias) ||
                    keyStore.containsAlias(RSA_ALIAS)) {
                throw new IllegalStateException(
                        "Android Keystore wallet key remains after secure deletion");
            }
        } catch (KeyStoreException ex) {
            throw new IllegalStateException("Unable to verify Android Keystore cleanup", ex);
        }

        if (!passwordPreferences.edit().clear().commit() ||
                !passwordPreferences.getAll().isEmpty()) {
            throw new IllegalStateException("Unable to clear stored wallet credentials");
        }
    }

    public static boolean keyExists(String wallet) throws BrokenPasswordStoreException {
        try {
            KeyStore keyStore = KeyStore.getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore.containsAlias(SecurityConstants.WALLET_PASS_KEY_PREFIX + wallet);
        } catch (IOException | NoSuchAlgorithmException | CertificateException |
                 KeyStoreException ex) {
            throw new BrokenPasswordStoreException(ex);
        }
    }

    @SuppressWarnings("deprecation")
    private static void createKeysJBMR2(Context context, String alias) throws NoSuchProviderException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException {

        Calendar start = new GregorianCalendar();
        Calendar end = new GregorianCalendar();
        end.add(Calendar.YEAR, 300);

        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(alias)
                .setSubject(new X500Principal("CN=" + alias))
                .setSerialNumber(BigInteger.valueOf(Math.abs(alias.hashCode())))
                .setStartDate(start.getTime()).setEndDate(end.getTime())
                .build();
        // defaults to 2048 bit modulus
        KeyPairGenerator kpGenerator = KeyPairGenerator.getInstance(
                SecurityConstants.TYPE_RSA,
                SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        kpGenerator.initialize(spec);
        KeyPair kp = kpGenerator.generateKeyPair();
        Timber.tag("createKeysJBMR2").d("preM Keys created");
    }

    private static void createKeysM(String alias) throws NoSuchProviderException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
        keyPairGenerator.initialize(
                new KeyGenParameterSpec.Builder(
                        alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .build());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
    }

    private static PrivateKey getPrivateKey(String alias) {
        try {
            KeyStore ks = KeyStore
                    .getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
            ks.load(null);
            //KeyStore.Entry entry = ks.getEntry(alias, null);
            PrivateKey privateKey = (PrivateKey) ks.getKey(alias, null);

            if (privateKey == null) {
                Timber.tag("createKeysM").i("No key found under alias: %s", alias);

                return null;
            }

            return privateKey;
        } catch (IOException | NoSuchAlgorithmException | CertificateException
                 | UnrecoverableEntryException | KeyStoreException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static PublicKey getPublicKey(String alias) {
        try {
            KeyStore ks = KeyStore
                    .getInstance(SecurityConstants.KEYSTORE_PROVIDER_ANDROID_KEYSTORE);
            ks.load(null);

            return ks.getCertificate(alias).getPublicKey();
        } catch (IOException | NoSuchAlgorithmException | CertificateException
                 | KeyStoreException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] encrypt(String alias, byte[] data) {
        try {
            PublicKey publicKey = getPublicKey(alias);
            Cipher cipher = Cipher.getInstance(SecurityConstants.CIPHER_RSA_ECB_PKCS1);

            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return cipher.doFinal(data);
        } catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException
                 | NoSuchAlgorithmException | NoSuchPaddingException ex) {
            Timber.tag(TAG).e(ex);
            return null;
        }
    }

    private static byte[] decrypt(String alias, byte[] data) {
        try {
            PrivateKey privateKey = getPrivateKey(alias);
            if (privateKey == null) return null;
            Cipher cipher = Cipher.getInstance(SecurityConstants.CIPHER_RSA_ECB_PKCS1);

            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(data);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 IllegalBlockSizeException | BadPaddingException ex) {
            Timber.tag(TAG).e(ex);
            return null;
        }
    }

    /**
     * Signs the data using the key pair stored in the Android Key Store. This
     * signature can be used with the data later to verify it was signed by this
     * application.
     *
     * @return The data signature generated
     */
    private static byte[] signData(byte[] data) throws NoSuchAlgorithmException,
            InvalidKeyException, SignatureException {
        PrivateKey privateKey = getPrivateKey(KeyStoreHelper.RSA_ALIAS);
        if (privateKey == null) return null;
        Signature s = Signature.getInstance(SecurityConstants.SIGNATURE_SHA256withRSA);
        s.initSign(privateKey);
        s.update(data);
        return s.sign();
    }

    public interface SecurityConstants {
        String KEYSTORE_PROVIDER_ANDROID_KEYSTORE = "AndroidKeyStore";
        String TYPE_RSA = "RSA";
        String SIGNATURE_SHA256withRSA = "SHA256withRSA";
        String CIPHER_RSA_ECB_PKCS1 = "RSA/ECB/PKCS1Padding";
        String WALLET_PASS_PREFS_NAME = "wallet";
        String WALLET_PASS_KEY_PREFIX = "walletKey-";
    }
}