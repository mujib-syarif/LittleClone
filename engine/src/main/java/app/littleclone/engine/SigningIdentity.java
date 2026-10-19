package app.littleclone.engine;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.io.ByteArrayInputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * A software RSA key plus self-signed X.509 certificate used to sign every clone.
 *
 * <p>All clones (and all of an app's split APKs) must be signed by the same identity, and a clone
 * can only be updated by an APK signed with the same key, so the identity is generated once and
 * stored by the app. It is built here, without BouncyCastle, so it can be unit-tested on a JVM.
 */
public final class SigningIdentity {

    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    public SigningIdentity(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /** Generates a fresh RSA-2048 identity valid for {@code years} years. */
    public static SigningIdentity generate(String commonName, int years) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();

        byte[] sigAlg = sequence(oid(SHA256_WITH_RSA), new byte[]{0x05, 0x00});
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F; // keep the INTEGER positive
        serial[0] |= 0x40; // and non-trivially long

        Calendar from = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        from.add(Calendar.DAY_OF_YEAR, -1); // tolerate a slightly wrong device clock
        Calendar to = (Calendar) from.clone();
        to.add(Calendar.YEAR, years);

        byte[] name = sequence(set(sequence(oid(OID_COMMON_NAME), utf8(commonName))));
        byte[] tbs = sequence(
                tagged(0, integer(BigInteger.valueOf(2).toByteArray())), // version: v3
                integer(serial),
                sigAlg,
                name,
                sequence(time(from.getTime()), time(to.getTime())),
                name,
                pair.getPublic().getEncoded());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] bitString = concat(new byte[]{0x00}, signature); // zero unused bits
        byte[] der = sequence(tbs, sigAlg, tlv(0x03, bitString));
        return new SigningIdentity(pair.getPrivate(), parseCertificate(der));
    }

    /** Rebuilds an identity from the two byte arrays returned by the encoders below. */
    public static SigningIdentity decode(byte[] pkcs8PrivateKey, byte[] certificateDer)
            throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8PrivateKey));
        return new SigningIdentity(key, parseCertificate(certificateDer));
    }

    public byte[] encodePrivateKey() {
        return privateKey.getEncoded();
    }

    public byte[] encodeCertificate() {
        try {
            return certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate parseCertificate(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    // ------------------------------------------------------------------ minimal DER writer

    private static final int[] SHA256_WITH_RSA = {1, 2, 840, 113549, 1, 1, 11};
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};

    private static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] tagged(int number, byte[]... parts) {
        return tlv(0xA0 | number, concat(parts));
    }

    private static byte[] integer(byte[] twosComplement) {
        return tlv(0x02, twosComplement);
    }

    private static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    /** UTCTime up to 2049, GeneralizedTime from 2050 on, as RFC 5280 requires. */
    private static byte[] time(Date date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTime(date);
        int year = c.get(Calendar.YEAR);
        boolean generalized = year >= 2050;
        String body = String.format(java.util.Locale.ROOT, generalized ? "%04d%02d%02d%02d%02d%02dZ" : "%02d%02d%02d%02d%02d%02dZ",
                generalized ? year : year % 100,
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        return tlv(generalized ? 0x18 : 0x17, body.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            long v = arcs[i];
            int groups = 1;
            while ((v >> (7 * groups)) != 0) {
                groups++;
            }
            for (int g = groups - 1; g >= 0; g--) {
                int b = (int) ((v >> (7 * g)) & 0x7F);
                out.write(g > 0 ? b | 0x80 : b);
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n <= 0xFF) {
            out.write(0x81);
            out.write(n);
        } else if (n <= 0xFFFF) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
