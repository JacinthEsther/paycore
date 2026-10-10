package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.authorization.entity.RoleName;

import java.util.List;

public record DemoStaff(
        String email,
        String firstName,
        String lastName,
        String phoneNumber,
        String role,
        String title,
        String duty
) {

    public static final DemoStaff SUPPORT =
            new DemoStaff("support@paycore.demo", "Kemi", "Lawal", "08030000004",
                    RoleName.SUPPORT, "Customer Support",
                    "Answers customers: looks up their profile, accounts and payments, and updates contact details");

    public static final DemoStaff COMPLIANCE =
            new DemoStaff("compliance@paycore.demo", "Ibrahim", "Musa", "08030000005",
                    RoleName.COMPLIANCE, "Compliance Officer",
                    "Reviews KYC and tier upgrades, activates accounts, freezes and suspends when something looks wrong");

    public static final DemoStaff OFFICER =
            new DemoStaff("ops.officer@paycore.demo", "Ada", "Okafor", "08030000002",
                    RoleName.OPERATIONS_OFFICER, "Operations Officer",
                    "Investigates stuck payments and reconciliation breaks and requests corrections (maker)");

    public static final DemoStaff SUPERVISOR =
            new DemoStaff("ops.supervisor@paycore.demo", "Bola", "Adeyemi", "08030000003",
                    RoleName.OPERATIONS_SUPERVISOR, "Operations Supervisor",
                    "Approves or rejects the officer's corrections and closes reconciliation breaks (checker)");

    public static final String ADMIN_TITLE = "System Admin";

    public static final String ADMIN_DUTY =
            "Gives staff their roles and takes them away; cannot review KYC, manage accounts or move money";

    public static final List<DemoStaff> ALL = List.of(SUPPORT, COMPLIANCE, OFFICER, SUPERVISOR);
}
