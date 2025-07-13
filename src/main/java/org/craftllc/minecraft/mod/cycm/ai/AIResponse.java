package org.craftllc.minecraft.mod.cycm.ai;

import com.google.gson.annotations.SerializedName;

public class AIResponse {
    @SerializedName("message")
    private String message;
    @SerializedName("runCommand")
    private String runCommand;

    public String getMessage() {
        return message;
    }

    public String getRunCommand() {
        return runCommand;
    }
}
