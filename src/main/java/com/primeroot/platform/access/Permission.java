package com.primeroot.platform.access;

public enum Permission {
	CATALOGUE_VIEW("View produce, grades, products, units and conversions"),
	CATALOGUE_MANAGE("Manage the business-wide product catalogue and units"),
	BRANCH_VIEW("View branches within the assigned scope"),
	BRANCH_MANAGE("Update branches within scope; create branches with business-wide scope"),
	USER_VIEW("View staff across the business"),
	USER_MANAGE("Approve and manage local staff access and branch assignments"),
	AUDIT_VIEW("View the business-wide administrative audit trail");

	private final String description;

	Permission(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
