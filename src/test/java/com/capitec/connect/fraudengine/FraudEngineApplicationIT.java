package com.capitec.connect.fraudengine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
class FraudEngineApplicationIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
			.withDatabaseName("fraud_db")
			.withUsername("postgres")
			.withPassword("postgres");

	@Container
	@ServiceConnection
	static final KafkaContainer kafka =
		new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

	@Test
	void contextLoads() {
	}

}
