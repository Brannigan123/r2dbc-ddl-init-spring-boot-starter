package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.config.R2dbcSchemaInitializer;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter.GenericEnumToStringConverter;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter.GenericStringToEnumConverterFactory;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;

@AutoConfiguration
@ConditionalOnClass(R2dbcEntityTemplate.class)
public class R2dbcDdlInitAutoConfiguration {

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
    @ConditionalOnMissingBean
    public R2dbcUpsertTemplate r2dbcUpsertTemplate(
            R2dbcEntityTemplate entityTemplate,
            RelationalMappingContext mappingContext) {
        return new R2dbcUpsertTemplate(entityTemplate, mappingContext);
    }
}
