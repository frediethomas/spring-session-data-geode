/*
 * Copyright 2014-2016 the original author or authors.
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

package org.springframework.session.data.gemfire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.GemFireCache;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.client.ClientCache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.gemfire.CacheFactoryBean;
import org.springframework.data.gemfire.client.ClientCacheFactoryBean;
import org.springframework.data.gemfire.client.PoolFactoryBean;
import org.springframework.data.gemfire.server.CacheServerFactoryBean;
import org.springframework.data.gemfire.support.ConnectionEndpoint;
import org.springframework.session.Session;
import org.springframework.session.data.gemfire.config.annotation.web.http.EnableGemFireHttpSession;
import org.springframework.session.data.gemfire.support.GemFireUtils;
import org.springframework.session.events.AbstractSessionEvent;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.SocketUtils;

/**
 * Integration test to test the functionality of a Pivotal GemFire cache client in a Spring Session application
 * using a specifically named Pivotal GemFire {@link org.apache.geode.cache.client.Pool} configured with
 * the 'poolName' attribute in the Spring Session Data Pivotal GemFire {@link EnableGemFireHttpSession} annotation.
 *
 * @author John Blum
 * @see org.junit.Test
 * @see org.junit.runner.RunWith
 * @see org.springframework.session.data.gemfire.AbstractGemFireIntegrationTests
 * @see org.springframework.session.data.gemfire.config.annotation.web.http.EnableGemFireHttpSession
 * @see org.springframework.session.data.gemfire.config.annotation.web.http.GemFireHttpSessionConfiguration
 * @see org.springframework.test.annotation.DirtiesContext
 * @see org.springframework.test.context.ContextConfiguration
 * @see org.springframework.test.context.junit4.SpringJUnit4ClassRunner
 * @see org.springframework.test.context.web.WebAppConfiguration
 * @see org.apache.geode.cache.Cache
 * @see org.apache.geode.cache.client.ClientCache
 * @see org.apache.geode.cache.client.Pool
 * @see org.apache.geode.cache.server.CacheServer
 * @since 1.3.0
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes =
	MultiPoolClientServerGemFireOperationsSessionRepositoryIntegrationTests.SpringSessionGemFireClientConfiguration.class)
@DirtiesContext
@WebAppConfiguration
public class MultiPoolClientServerGemFireOperationsSessionRepositoryIntegrationTests
		extends AbstractGemFireIntegrationTests {

	private static final int MAX_INACTIVE_INTERVAL_IN_SECONDS = 1;

	private static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");

	private static File processWorkingDirectory;

	private static Process gemfireServer;

	private static final String SPRING_SESSION_GEMFIRE_REGION_NAME = "TestMultiPoolClientServerSessions";

	@Autowired
	private SessionEventListener sessionEventListener;

	@BeforeClass
	public static void startGemFireServer() throws IOException {

		final long t0 = System.currentTimeMillis();

		final int port = SocketUtils.findAvailableTcpPort();

		System.err.printf("Starting a Pivotal GemFire Server on [%1$s] listening on port [%2$d]%n",
			SpringSessionGemFireServerConfiguration.SERVER_HOSTNAME, port);

		System.setProperty("spring.session.data.gemfire.port", String.valueOf(port));

		String processWorkingDirectoryPathname = String.format("gemfire-multipool-client-server-tests-%1$s",
			TIMESTAMP.format(new Date()));

		processWorkingDirectory = createDirectory(processWorkingDirectoryPathname);

		gemfireServer = run(SpringSessionGemFireServerConfiguration.class, processWorkingDirectory,
			String.format("-Dspring.session.data.gemfire.port=%1$d", port));

		assertThat(waitForCacheServerToStart(SpringSessionGemFireServerConfiguration.SERVER_HOSTNAME, port)).isTrue();

		System.err.printf("GemFire Server [startup time = %1$d ms]%n", System.currentTimeMillis() - t0);
	}

	@AfterClass
	public static void stopGemFireServerAndDeleteArtifacts() {

		if (gemfireServer != null) {
			gemfireServer.destroyForcibly();
			System.err.printf("GemFire Server [exit code = %1$d]%n",
				waitForProcessToStop(gemfireServer, processWorkingDirectory));
		}

		if (Boolean.valueOf(System.getProperty("spring.session.data.gemfire.fork.clean", Boolean.TRUE.toString()))) {
			FileSystemUtils.deleteRecursively(processWorkingDirectory);
		}

		assertThat(waitForClientCacheToClose(DEFAULT_WAIT_DURATION)).isTrue();
	}

	@Before
	public void setup() {

		assertThat(GemFireUtils.isClient(gemfireCache)).isTrue();

		Region<Object, Session> springSessionGemFireRegion =
			gemfireCache.getRegion(SPRING_SESSION_GEMFIRE_REGION_NAME);

		assertThat(springSessionGemFireRegion).isNotNull();

		RegionAttributes<Object, Session> springSessionGemFireRegionAttributes =
			springSessionGemFireRegion.getAttributes();

		assertThat(springSessionGemFireRegionAttributes).isNotNull();
		assertThat(springSessionGemFireRegionAttributes.getDataPolicy()).isEqualTo(DataPolicy.EMPTY);
	}

	protected static ConnectionEndpoint newConnectionEndpoint(String host, int port) {
		return new ConnectionEndpoint(host, port);
	}

	@Test
	public void getExistingNonExpiredSessionBeforeAndAfterExpiration() {

		Session expectedSession = save(touch(createSession()));

		AbstractSessionEvent sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isInstanceOf(SessionCreatedEvent.class);
		assertThat(sessionEvent.<Session>getSession()).isEqualTo(expectedSession);
		assertThat(this.sessionEventListener.<SessionCreatedEvent>getSessionEvent()).isNull();

		Session savedSession = this.gemfireSessionRepository.findById(expectedSession.getId());

		assertThat(savedSession).isEqualTo(expectedSession);

		this.sessionEventListener.getSessionEvent();

		sessionEvent = this.sessionEventListener.waitForSessionEvent(
			TimeUnit.SECONDS.toMillis(MAX_INACTIVE_INTERVAL_IN_SECONDS + 1));

		assertThat(sessionEvent).isInstanceOf(SessionExpiredEvent.class);
		assertThat(sessionEvent.getSessionId()).isEqualTo(expectedSession.getId());

		Session expiredSession = this.gemfireSessionRepository.findById(expectedSession.getId());

		assertThat(expiredSession).isNull();
	}

	@EnableGemFireHttpSession(regionName = SPRING_SESSION_GEMFIRE_REGION_NAME, poolName = "serverPool",
		maxInactiveIntervalInSeconds = MAX_INACTIVE_INTERVAL_IN_SECONDS)
	@SuppressWarnings("unused")
	static class SpringSessionGemFireClientConfiguration {

		@Bean
		PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		@Bean
		Properties gemfireProperties() {

			Properties gemfireProperties = new Properties();

			gemfireProperties.setProperty("name", name());
			gemfireProperties.setProperty("log-level", GEMFIRE_LOG_LEVEL);

			return gemfireProperties;
		}

		String name() {
			return SpringSessionGemFireClientConfiguration.class.getName();
		}

		@Bean
		ClientCacheFactoryBean gemfireCache() {

			ClientCacheFactoryBean gemfireCache = new ClientCacheFactoryBean();

			gemfireCache.setClose(true);
			gemfireCache.setPoolName("gemfirePool");
			gemfireCache.setProperties(gemfireProperties());

			return gemfireCache;
		}

		@Bean
		PoolFactoryBean gemfirePool() {

			PoolFactoryBean poolFactory = new PoolFactoryBean();

			poolFactory.setFreeConnectionTimeout(5000); // 5 seconds
			poolFactory.setKeepAlive(false);
			poolFactory.setMinConnections(0);
			poolFactory.setReadTimeout(500);

			// deliberately set to a non-existing Pivotal GemFire (Cache) Server
			poolFactory.addServers(newConnectionEndpoint("localhost", 53135));

			return poolFactory;
		}

		@Bean
		PoolFactoryBean serverPool(@Value("${spring.session.data.gemfire.port:"
				+ DEFAULT_GEMFIRE_SERVER_PORT + "}") int port) {

			PoolFactoryBean poolFactory = new PoolFactoryBean();

			poolFactory.setFreeConnectionTimeout(5000); // 5 seconds
			poolFactory.setKeepAlive(false);
			poolFactory.setMaxConnections(SpringSessionGemFireServerConfiguration.MAX_CONNECTIONS);
			poolFactory.setPingInterval(TimeUnit.SECONDS.toMillis(5));
			poolFactory.setReadTimeout(2000); // 2 seconds
			poolFactory.setRetryAttempts(1);
			poolFactory.setSubscriptionEnabled(true);
			poolFactory.setThreadLocalConnections(false);

			poolFactory.addServers(newConnectionEndpoint(
				SpringSessionGemFireServerConfiguration.SERVER_HOSTNAME, port));

			return poolFactory;
		}

		@Bean
		public AbstractGemFireIntegrationTests.SessionEventListener sessionEventListener() {
			return new AbstractGemFireIntegrationTests.SessionEventListener();
		}

		// used for debugging purposes
		@SuppressWarnings("resource")
		public static void main(final String[] args) {

			ConfigurableApplicationContext applicationContext = new AnnotationConfigApplicationContext(
				SpringSessionGemFireClientConfiguration.class);

			applicationContext.registerShutdownHook();

			ClientCache clientCache = applicationContext.getBean(ClientCache.class);

			for (InetSocketAddress server : clientCache.getCurrentServers()) {
				System.err.printf("GemFire Server [host: %1$s, port: %2$d]%n",
					server.getHostName(), server.getPort());
			}
		}
	}

	@EnableGemFireHttpSession(regionName = SPRING_SESSION_GEMFIRE_REGION_NAME,
		maxInactiveIntervalInSeconds = MAX_INACTIVE_INTERVAL_IN_SECONDS)
	@SuppressWarnings("unused")
	static class SpringSessionGemFireServerConfiguration {

		static final int MAX_CONNECTIONS = 50;
		static final String SERVER_HOSTNAME = "localhost";

		@Bean
		static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		@Bean
		Properties gemfireProperties() {

			Properties gemfireProperties = new Properties();

			gemfireProperties.setProperty("name", name());
			gemfireProperties.setProperty("mcast-port", "0");
			gemfireProperties.setProperty("log-file", "server.log");
			gemfireProperties.setProperty("log-level", GEMFIRE_LOG_LEVEL);

			return gemfireProperties;
		}

		String name() {
			return SpringSessionGemFireServerConfiguration.class.getName();
		}

		@Bean
		CacheFactoryBean gemfireCache() {

			CacheFactoryBean gemfireCache = new CacheFactoryBean();

			gemfireCache.setClose(true);
			gemfireCache.setProperties(gemfireProperties());

			return gemfireCache;
		}

		@Bean
		CacheServerFactoryBean gemfireCacheServer(GemFireCache gemfireCache,
			@Value("${spring.session.data.gemfire.port:" + DEFAULT_GEMFIRE_SERVER_PORT + "}") int port) {

			CacheServerFactoryBean cacheServerFactory = new CacheServerFactoryBean();

			cacheServerFactory.setAutoStartup(true);
			cacheServerFactory.setBindAddress(SERVER_HOSTNAME);
			cacheServerFactory.setCache((Cache) gemfireCache);
			cacheServerFactory.setMaxConnections(MAX_CONNECTIONS);
			cacheServerFactory.setPort(port);

			return cacheServerFactory;
		}

		@SuppressWarnings("resource")
		public static void main(final String[] args) throws IOException {

			AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				SpringSessionGemFireServerConfiguration.class);

			context.registerShutdownHook();

			writeProcessControlFile(WORKING_DIRECTORY);
		}
	}
}
