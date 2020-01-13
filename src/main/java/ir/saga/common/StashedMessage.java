package ir.saga.common;


import ir.saga.message.Message;

public class StashedMessage {
    private String sagaType;
    private final String sagaId;
    private final Message message;

    public String getSagaType() {
        return sagaType;
    }

    public StashedMessage(String sagaType, String sagaId, Message message) {
        this.sagaType = sagaType;
        this.sagaId = sagaId;
        this.message = message;
    }

    public String getSagaId() {
        return sagaId;
    }

    public Message getMessage() {
        return message;
    }
}
