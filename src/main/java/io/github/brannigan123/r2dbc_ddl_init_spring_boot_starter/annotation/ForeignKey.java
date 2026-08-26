package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.enums.OnDelete;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.enums.OnUpdate;

@Target({ ElementType.TYPE, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ForeignKeys.class)
public @interface ForeignKey {
    String name() default "";

    String table();

    String[] columns() default {};

    String[] referencedColumns() default {};

    String column() default "id";

    OnDelete onDelete() default OnDelete.RESTRICT;

    OnUpdate onUpdate() default  OnUpdate.CASCADE;
}