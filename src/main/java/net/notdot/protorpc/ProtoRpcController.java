package net.notdot.protorpc;

import com.google.protobuf.RpcCallback;

public class ProtoRpcController implements com.google.protobuf.RpcController {
	protected String error = null;
	protected int application_error = 0;
	
	public int getApplicationError() {
		return application_error;
	}

	public void setApplicationError(int application_error) {
		this.application_error = application_error;
	}

	public ProtoRpcController() {
	}
	
	public String errorText() {
		return error;
	}

	public boolean failed() {
		return error != null;
	}

	public boolean isCanceled() {
		return false;
	}

	public void notifyOnCancel(RpcCallback<Object> arg0) {
	}

	public void reset() {
		error = null;
	}

	public void setFailed(String arg0) {
		error = arg0;
	}

	public void startCancel() {
	}
}