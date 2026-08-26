package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.enums;

public enum SearchWeight {
    HIGH("A"),
    MEDIUM("B"),
    LOW("C"),
    DEFAULT("D"),
    A("A"),
    B("B"),
    C("C"),
    D("D");

    private final String code;

    SearchWeight(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}