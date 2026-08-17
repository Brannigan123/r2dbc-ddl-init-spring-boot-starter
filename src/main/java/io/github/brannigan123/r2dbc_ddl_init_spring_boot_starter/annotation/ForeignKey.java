package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.enums.OnDelete;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ForeignKey {
    String table();

    String column() default "id";

    OnDelete onDelete() default OnDelete.NO_ACTION;
}
