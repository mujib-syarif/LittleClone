package app.littleclone.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.Signature;
import java.util.Date;
import org.junit.Test;

public class SigningIdentityTest {

    @Test
    public void generatedCertificateIsValidAndSelfSigned() throws Exception {
        SigningIdentity id = SigningIdentity.generate("Little Clone", 30);
        id.certificate.verify(id.certificate.getPublicKey()); // throws if the signature is wrong
        assertEquals("CN=Little Clone", id.certificate.getSubjectX500Principal().getName());
        assertEquals(id.certificate.getSubjectX500Principal(), id.certificate.getIssuerX500Principal());
        assertEquals("SHA256withRSA", id.certificate.getSigAlgName());
        assertEquals(3, id.certificate.getVersion());
        id.certificate.checkValidity(new Date());
        // 30 years ahead is past 2049, which exercises the GeneralizedTime branch.
        assertTrue(id.certificate.getNotAfter().getTime() > new Date().getTime() + 29L * 365 * 86400_000L);
    }

    @Test
    public void keyMatchesCertificateAndSurvivesEncoding() throws Exception {
        SigningIdentity id = SigningIdentity.generate("Little Clone", 30);
        SigningIdentity copy = SigningIdentity.decode(id.encodePrivateKey(), id.encodeCertificate());
        assertArrayEquals(id.encodeCertificate(), copy.encodeCertificate());

        byte[] data = {1, 2, 3, 4};
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(copy.privateKey);
        s.update(data);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(id.certificate.getPublicKey());
        v.update(data);
        assertTrue(v.verify(sig));
    }

    @Test
    public void shortValidityUsesUtcTime() throws Exception {
        SigningIdentity id = SigningIdentity.generate("X", 1);
        id.certificate.verify(id.certificate.getPublicKey());
        id.certificate.checkValidity(new Date());
    }
}
