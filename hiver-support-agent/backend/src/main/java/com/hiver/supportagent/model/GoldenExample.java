package com.hiver.supportagent.model;

public class GoldenExample {
    private int id;
    private String customerText;
    private String goldIntent;
    private boolean goldEscalate;
    private String sampleGroup;
    private String notes;

    public GoldenExample() {}

    public GoldenExample(int id, String customerText, String goldIntent, boolean goldEscalate, String sampleGroup, String notes) {
        this.id = id;
        this.customerText = customerText;
        this.goldIntent = goldIntent;
        this.goldEscalate = goldEscalate;
        this.sampleGroup = sampleGroup;
        this.notes = notes;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCustomerText() { return customerText; }
    public void setCustomerText(String customerText) { this.customerText = customerText; }

    public String getGoldIntent() { return goldIntent; }
    public void setGoldIntent(String goldIntent) { this.goldIntent = goldIntent; }

    public boolean isGoldEscalate() { return goldEscalate; }
    public void setGoldEscalate(boolean goldEscalate) { this.goldEscalate = goldEscalate; }

    public String getSampleGroup() { return sampleGroup; }
    public void setSampleGroup(String sampleGroup) { this.sampleGroup = sampleGroup; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
