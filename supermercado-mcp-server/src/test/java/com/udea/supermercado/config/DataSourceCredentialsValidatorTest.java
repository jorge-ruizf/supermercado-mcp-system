package com.udea.supermercado.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceCredentialsValidatorTest {

	private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

	@Test
	void acceptsConfiguredCredentials() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(DataSourceCredentialsValidator.USERNAME_KEY, "super_app")
				.withProperty(DataSourceCredentialsValidator.PASSWORD_KEY, "supersecret");

		assertThatCode(() -> validator(environment).postProcessBeanFactory(this.beanFactory))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsBlankUsername() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(DataSourceCredentialsValidator.USERNAME_KEY, "   ")
				.withProperty(DataSourceCredentialsValidator.PASSWORD_KEY, "supersecret");

		assertThatThrownBy(() -> validator(environment).postProcessBeanFactory(this.beanFactory))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DB_USERNAME");
	}

	@Test
	void rejectsMissingPassword() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(DataSourceCredentialsValidator.USERNAME_KEY, "super_app");

		assertThatThrownBy(() -> validator(environment).postProcessBeanFactory(this.beanFactory))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DB_PASSWORD");
	}

	@Test
	void rejectsUnresolvedPlaceholder() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(DataSourceCredentialsValidator.USERNAME_KEY, "super_app")
				.withProperty(DataSourceCredentialsValidator.PASSWORD_KEY, "${DB_PASSWORD}");

		assertThatThrownBy(() -> validator(environment).postProcessBeanFactory(this.beanFactory))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("marcador sin resolver");
	}

	@Test
	void neverIncludesCredentialValuesInTheMessage() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(DataSourceCredentialsValidator.USERNAME_KEY, "super_app")
				.withProperty(DataSourceCredentialsValidator.PASSWORD_KEY, "${DB_PASSWORD}");

		assertThatThrownBy(() -> validator(environment).postProcessBeanFactory(this.beanFactory))
				.hasMessageNotContaining("super_app");
	}

	private DataSourceCredentialsValidator validator(MockEnvironment environment) {
		DataSourceCredentialsValidator validator = new DataSourceCredentialsValidator();
		validator.setEnvironment(environment);
		return validator;
	}

}
