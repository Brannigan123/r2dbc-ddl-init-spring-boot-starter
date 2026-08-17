package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class GenericStringToEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
        return new StringToEnumConverter((Class) targetType);
    }

    private static class StringToEnumConverter<T extends Enum<T>> implements Converter<String, T> {

        private final Class<T> enumType;

        public StringToEnumConverter(Class<T> enumType) {
            this.enumType = enumType;
        }

        @Override
        public T convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            return Enum.valueOf(this.enumType, source.trim());
        }
    }
}
