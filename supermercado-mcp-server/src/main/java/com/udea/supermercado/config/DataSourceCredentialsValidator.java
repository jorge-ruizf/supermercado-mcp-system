package com.udea.supermercado.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DataSourceCredentialsValidator implements BeanFactoryPostProcessor, EnvironmentAware {

	static final String USERNAME_KEY = "spring.datasource.username";
	static final String PASSWORD_KEY = "spring.datasource.password";

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		requireConfigured(USERNAME_KEY);
		requireConfigured(PASSWORD_KEY);
	}

	private void requireConfigured(String propertyKey) {
		final String value;
		try {
			value = this.environment.getProperty(propertyKey);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(unresolvedMessage(propertyKey), ex);
		}
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Las credenciales de la base de datos no están configuradas: '"
					+ propertyKey + "' está vacío. Exporta DB_USERNAME y DB_PASSWORD antes de iniciar.");
		}
		if (value.contains("${")) {
			throw new IllegalStateException(unresolvedMessage(propertyKey));
		}
	}

	private String unresolvedMessage(String propertyKey) {
		return "Las credenciales de la base de datos no están configuradas: '" + propertyKey
				+ "' contiene un marcador sin resolver. Exporta DB_USERNAME y DB_PASSWORD antes de iniciar.";
	}

}
