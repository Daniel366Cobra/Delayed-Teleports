package dev.itsmeow.delayedteleports.util;

public class HereTeleport extends Teleport {

    private final String requester;
    private final String brought;

    public HereTeleport(String requester, String brought) {
        super(Teleport.TPType.TP_BRING);
        this.requester = requester;
        this.brought = brought;
    }

    public String getRequester() {
        return requester;
    }

    @Override
    public String getSubject() {
        return brought;
    }

}
