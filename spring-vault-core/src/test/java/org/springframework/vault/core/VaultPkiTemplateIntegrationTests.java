/*
 * Copyright 2016-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.core;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.assertj.core.util.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StreamUtils;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultPkiOperations.Encoding;
import org.springframework.vault.support.Certificate;
import org.springframework.vault.support.CertificateBundle;
import org.springframework.vault.support.VaultCertificateRequest;
import org.springframework.vault.support.VaultCertificateResponse;
import org.springframework.vault.support.VaultSignCertificateRequestResponse;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.springframework.vault.util.Settings.findWorkDir;

/**
 * Integration tests for {@link VaultPkiTemplate} through {@link VaultPkiOperations}.
 *
 * @author Mark Paluch
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
public class VaultPkiTemplateIntegrationTests extends IntegrationTestSupport {

	private static final Version NO_TTL_UNIT_REQUIRED_FROM = Version.parse("0.7.3");

	@Autowired
	private VaultOperations vaultOperations;

	private VaultPkiOperations pkiOperations;

	@Before
	public void before() {

		pkiOperations = vaultOperations.opsForPki();

		if (!prepare().hasSecret("pki")) {
			prepare().mountSecret("pki");
		}

		File workDir = findWorkDir(new File(System.getProperty("user.dir")));
		String cert = Files.contentOf(
				new File(workDir, "ca/certs/intermediate.cert.pem"), "US-ASCII");
		String key = Files.contentOf(new File(workDir,
				"ca/private/intermediate.decrypted.key.pem"), "US-ASCII");

		Map<String, String> pembundle = Collections
				.singletonMap("pem_bundle", cert + key);

		vaultOperations.write("pki/config/ca", pembundle);

		Map<String, String> role = new HashMap<String, String>();
		role.put("allowed_domains", "localhost,example.com");
		role.put("allow_subdomains", "true");
		role.put("allow_localhost", "true");
		role.put("allow_ip_sans", "true");
		role.put("max_ttl", "72h");

		vaultOperations.write("pki/roles/testrole", role);
	}

	@Test
	public void issueCertificateShouldCreateCertificate() {

		VaultCertificateRequest request = VaultCertificateRequest
				.create("hello.example.com");

		VaultCertificateResponse certificateResponse = pkiOperations.issueCertificate(
				"testrole", request);

		CertificateBundle data = certificateResponse.getData();

		assertThat(data.getPrivateKey()).isNotEmpty();
		assertThat(data.getCertificate()).isNotEmpty();
		assertThat(data.getIssuingCaCertificate()).isNotEmpty();
		assertThat(data.getSerialNumber()).isNotEmpty();
		assertThat(data.getX509Certificate().getSubjectX500Principal().getName())
				.isEqualTo("CN=hello.example.com");
	}

	@Test
	public void issueCertificateWithTtlShouldCreateCertificate() {

		assumeTrue(prepare().getVersion().isGreaterThanOrEqualTo(
				NO_TTL_UNIT_REQUIRED_FROM));

		VaultCertificateRequest request = VaultCertificateRequest.builder()
				.ttl(Duration.ofHours(48)).commonName("hello.example.com").build();

		VaultCertificateResponse certificateResponse = pkiOperations.issueCertificate(
				"testrole", request);

		X509Certificate certificate = certificateResponse.getData().getX509Certificate();

		Instant now = Instant.now();
		assertThat(certificate.getNotAfter()).isAfter(
				Date.from(now.plus(40, ChronoUnit.HOURS))).isBefore(
				Date.from(now.plus(50, ChronoUnit.HOURS)));
	}

	@Test
	public void signShouldSignCsr() {

		String csr = "-----BEGIN CERTIFICATE REQUEST-----\n"
				+ "MIICzTCCAbUCAQAwgYcxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpTb21lLVN0YXRl\n"
				+ "MRUwEwYDVQQHEwxTYW4gVmF1bHRpbm8xFTATBgNVBAoTDFNwcmluZyBWYXVsdDEY\n"
				+ "MBYGA1UEAxMPY3NyLmV4YW1wbGUuY29tMRswGQYJKoZIhvcNAQkBFgxzcHJpbmdA\n"
				+ "dmF1bHQwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDVlDBT1gAONIp4\n"
				+ "GQQ7BWDeqNzlscWqu5oQyfvw6oNFZzYWGVTgX/n72biv8d1Wx30MWpVYhbL0mk9m\n"
				+ "Uu15elMZHPb4F4bk8VDSiB9527SwAd/QpkNC1RsPp2h6g2LvGPJ2eidHSlLtF2To\n"
				+ "A4i6z0K0++nvYKSf9Af0sod2Z51xc9uPj/oN5z/8BQuGoCBpxJqgl7N/csMICixY\n"
				+ "2fQcCUbdPPqE9INIInUHe3mPE/yvxko9aYGZ5jnrdZyiQaRRKBdWpvbRLKXQ78Fz\n"
				+ "vXR3G33yn9JAN6wl1A916DiXzy2xHT19vyAn1hBUj2M6KFXChQ30oxTyTOqHCMLP\n"
				+ "m/BSEOsPAgMBAAGgADANBgkqhkiG9w0BAQsFAAOCAQEAYFssueiUh3YGxnXcQ4dp\n"
				+ "ZqVWeVyOuGGaFJ4BA0drwJ9Mt/iNmPUTGE2oBNnh2R7e7HwGcNysFHZZOZBEQ0Hh\n"
				+ "Vn93GO7cfaTOetK0VtDqis1VFQD0eVPWf5s6UqT/+XGrFRhwJ9hM+2FQSrUDFecs\n"
				+ "+/605n1rD7qOj3vkGrtwvEUrxyRaQaKpPLHmVHENqV6F1NsO3Z27f2FWWAZF2VKN\n"
				+ "cCQQJNc//DbIN3J3JSElpIDBDHctoBoQVnMiwpCbSA+CaAtlWYJKnAfhTKeqnNMy\n"
				+ "qf3ACZ+1sBIuqSP7dEJ2KfIezaCPQ88+PAloRB52LFa+iq3yI7F5VzkwAvQFnTi+\n"
				+ "cQ==\n" + "-----END CERTIFICATE REQUEST-----";

		VaultCertificateRequest request = VaultCertificateRequest
				.create("hello.example.com");

		VaultSignCertificateRequestResponse certificateResponse = pkiOperations
				.signCertificateRequest("testrole", csr, request);

		Certificate data = certificateResponse.getData();

		assertThat(data.getCertificate()).isNotEmpty();
		assertThat(data.getIssuingCaCertificate()).isNotEmpty();
		assertThat(data.getSerialNumber()).isNotEmpty();
		assertThat(data.getX509Certificate().getSubjectX500Principal().getName())
				.isEqualTo("CN=csr.example.com");
		assertThat(data.createTrustStore()).isNotNull();
	}

	@Test(expected = VaultException.class)
	public void issueCertificateFail() {

		VaultCertificateRequest request = VaultCertificateRequest.create("not.supported");

		pkiOperations.issueCertificate("testrole", request);
	}

	@Test
	public void shouldRevokeCertificate() throws Exception {

		VaultCertificateRequest request = VaultCertificateRequest
				.create("foo.example.com");

		VaultCertificateResponse certificateResponse = pkiOperations.issueCertificate(
				"testrole", request);

		BigInteger serial = new BigInteger(certificateResponse.getData()
				.getSerialNumber().replaceAll("\\:", ""), 16);
		pkiOperations.revoke(certificateResponse.getData().getSerialNumber());

		try (InputStream in = pkiOperations.getCrl(Encoding.DER)) {

			CertificateFactory cf = CertificateFactory.getInstance("X.509");

			X509CRL crl = (X509CRL) cf.generateCRL(in);

			assertThat(crl.getRevokedCertificate(serial)).isNotNull();
		}
	}

	@Test
	public void shouldReturnCrl() throws Exception {

		try (InputStream in = pkiOperations.getCrl(Encoding.DER)) {

			CertificateFactory cf = CertificateFactory.getInstance("X.509");

			assertThat(cf.generateCRL(in)).isInstanceOf(X509CRL.class);
		}

		try (InputStream crl = pkiOperations.getCrl(Encoding.PEM)) {

			byte[] bytes = StreamUtils.copyToByteArray(crl);
			assertThat(bytes).isNotEmpty();
		}
	}
}
