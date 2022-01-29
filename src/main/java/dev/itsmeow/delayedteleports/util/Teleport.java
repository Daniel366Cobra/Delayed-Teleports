package dev.itsmeow.delayedteleports.util;

import com.sun.org.apache.xpath.internal.operations.Bool;

public abstract class Teleport {

    public final TPType TYPE;

    public Teleport(TPType type) {
        this.TYPE = type;
    }

    //Requester is the one who initiates the request,
    //Subject is the request's target.
    //For a ToTeleport, Subject is the destination player for the Requester.
    //For a HereTeleport, Subject is player being brought over by the Requester.
    public abstract String getRequester();
    public abstract String getSubject();


    public enum TPType {
        TP_TO,
        TP_BRING;
    }

}