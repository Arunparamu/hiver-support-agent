package com.hiver.supportagent.model;

/**
 * One historical (customer message -> brand's resolution reply) pair, used as
 * the grounding corpus for retrieval-augmented reply drafting.
 */
public class HistoricalConversation {
    private String tweetId;
    private String customerText;
    private String brandReply;
    private String intent;
    private String orderId;

    public HistoricalConversation() {}

    public HistoricalConversation(String tweetId, String customerText, String brandReply, String intent, String orderId) {
        this.tweetId = tweetId;
        this.customerText = customerText;
        this.brandReply = brandReply;
        this.intent = intent;
        this.orderId = orderId;
    }

    public String getTweetId() { return tweetId; }
    public void setTweetId(String tweetId) { this.tweetId = tweetId; }

    public String getCustomerText() { return customerText; }
    public void setCustomerText(String customerText) { this.customerText = customerText; }

    public String getBrandReply() { return brandReply; }
    public void setBrandReply(String brandReply) { this.brandReply = brandReply; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
}
