package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.enums;

public enum OnUpdate {
    NO_ACTION("NO ACTION"),
    RESTRICT("RESTRICT"),
    CASCADE("CASCADE"),
    SET_NULL("SET NULL"),
    SET_DEFAULT("SET DEFAULT");

    private final String action;

    OnUpdate(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }
}
