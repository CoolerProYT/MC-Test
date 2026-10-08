package com.coolerpromc.mctest.rpc;

/**
 * An expected, user-facing failure (bad params, wrong game state). Reported without a stack trace.
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message, null, false, false);
    }
}
