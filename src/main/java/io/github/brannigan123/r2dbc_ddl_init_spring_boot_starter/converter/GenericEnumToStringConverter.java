package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class GenericEnumToStringConverter implements Converter<Enum<?>, String> {

    @Override
    public String convert(Enum<?> source) {
        return source != null ? source.name() : null;
    }
}
