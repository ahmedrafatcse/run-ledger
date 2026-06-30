package com.runledger;

import org.springframework.boot.SpringApplication;

public class TestRunLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.from(RunLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
