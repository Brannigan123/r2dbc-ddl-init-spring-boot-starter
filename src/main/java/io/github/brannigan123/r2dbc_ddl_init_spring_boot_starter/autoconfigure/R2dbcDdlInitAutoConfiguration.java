package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactoryBean;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.config.ApplicationContextProvider;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.config.R2dbcSchemaInitializer;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter.GenericEnumToStringConverter;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter.GenericStringToEnumConverterFactory;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository.CommonR2dbcRepositoryImpl;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;

@AutoConfiguration
@ConditionalOnClass(R2dbcEntityTemplate.class)
@EnableR2dbcRepositories(repositoryBaseClass = CommonR2dbcRepositoryImpl.class)
public class R2dbcDdlInitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationContextProvider applicationContextProvider() {
        return new ApplicationContextProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcSchemaInitializer r2dbcSchemaInitializer(R2dbcEntityTemplate entityTemplate,
            RelationalMappingContext mappingContext) {
        return new R2dbcSchemaInitializer(entityTemplate, mappingContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        List<Object> converters = new ArrayList<>();
        converters.add(new GenericEnumToStringConverter());
        converters.add(new GenericStringToEnumConverterFactory());
        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, converters);
    }

    @Bean
    public static BeanFactoryPostProcessor r2dbcRepositoryBaseClassPostProcessor() {
        return beanFactory -> {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
                if (R2dbcRepositoryFactoryBean.class.getName().equals(beanDefinition.getBeanClassName())) {
                    beanDefinition.getPropertyValues().add("repositoryBaseClass", CommonR2dbcRepositoryImpl.class);
                }
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcUpsertTemplate r2dbcUpsertTemplate(
            R2dbcEntityTemplate entityTemplate,
            RelationalMappingContext mappingContext) {
        return new R2dbcUpsertTemplate(entityTemplate, mappingContext);
    }
}
