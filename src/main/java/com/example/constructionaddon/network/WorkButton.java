package com.example.constructionaddon.network;

/** Every on/off work control, each its own key binding, sent together as a bit mask (see
 * WorkInputPayload). */
public enum WorkButton {
	/** Default Z: the machine's main action (dig, drive, couple...; the rotary pile driver's leader). */
	PRIMARY("work_primary"),
	/** Default X: the secondary action (leader, dump...; the rotary pile driver's grip / release). */
	SECONDARY("work_secondary"),
	/** Default right mouse button: rotary pile driver - drive the pile in. */
	PENETRATE("work_penetrate"),
	/** Default left mouse button: rotary pile driver - pull the pile up. */
	EXTRACT("work_extract"),
	/** Default I: rotary pile driver - drive rod / follower. */
	TOOL("work_tool");

	private final String id;

	WorkButton(String id) {
		this.id = id;
	}

	public String translationKey() {
		return "key.constructionaddon." + this.id;
	}

	public int bit() {
		return 1 << this.ordinal();
	}
}
