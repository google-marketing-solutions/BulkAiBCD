package com.bulkaibcd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BulkAibcdApplication {

  public static void main(String[] args) {
    SpringApplication.run(BulkAibcdApplication.class, args);
  }
}
