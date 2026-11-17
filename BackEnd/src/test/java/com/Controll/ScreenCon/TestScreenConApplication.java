package com.Controll.ScreenCon;

import org.springframework.boot.SpringApplication;

public class TestScreenConApplication {

	public static void main(String[] args) {
		SpringApplication.from(ScreenConApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
