package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.authorization.entity.RoleName;

import java.util.List;

/**
 * The shared operations officers of the Developer Preview, seeded next to
 * the demo admin with the same (public) password. There are two because
 * corrections need two people: one requests, the other approves.
 */
public record DemoStaff(
        String email,
        String firstName,
        String lastName,
        String phoneNumber,
        String duty
) {

    public static final String ROLE = RoleName.OPERATIONS;

    public static final DemoStaff OFFICER =
            new DemoStaff("ops.officer@paycore.demo", "Ada", "Okafor", "08030000002",
                    "Operations officer: requests reversals and adjustments (maker)");

    public static final DemoStaff SUPERVISOR =
            new DemoStaff("ops.supervisor@paycore.demo", "Bola", "Adeyemi", "08030000003",
                    "Operations supervisor: approves or rejects them (checker)");

    public static final List<DemoStaff> ALL = List.of(OFFICER, SUPERVISOR);
}
