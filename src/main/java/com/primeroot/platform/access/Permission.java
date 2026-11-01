package com.primeroot.platform.access;

public enum Permission {
	SALE_CREATE("Post fully paid sales in exactly one assigned branch; view own sales and performance"),
	SALES_VIEW("View sales and staff performance within the assigned branch scope"),
	SALE_VOID("Void an entire sale after returned goods and a confirmed manual refund"),
	PROFIT_VIEW("View purchase-cost, stock valuation and gross-profit reports within branch scope"),
	STOCK_VIEW("View inventory, batches and movements within the assigned branch scope"),
	STOCK_RECEIVE("Receive stock into branches within the assigned scope"),
	STOCK_ADJUST("Correct stock, reverse movements and configure branch stock minimums"),
	STOCK_SPOILAGE("Record batch spoilage within the assigned branch scope"),
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
