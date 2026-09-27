package com.example.constructionaddon.network;

/** Every analog work control, each with its own pair of key bindings (so axes sharing default
 * keys stay independently rebindable). Values are -1 / 0 / +1, sent packed 2 bits per axis. */
public enum WorkAxis {
	/** Upper-structure / chute swing. +1 = left. Default Left / Right. */
	SWING("work_axis_left", "work_axis_right"),
	/** Generic vertical: dozer blade, pile target depth, mixer chute tilt. Default Up / Down. */
	VERTICAL("work_axis_up", "work_axis_down"),
	/** Backhoe boom. +1 = raise. Default Up / Down. */
	BOOM("work_boom_up", "work_boom_down"),
	/** Backhoe arm. +1 = in. Default , / . */
	ARM("work_axis_arm_in", "work_axis_arm_out"),
	/** Crane luffing (boom angle). +1 = raise. Default Up / Down. */
	LUFF("work_luff_up", "work_luff_down"),
	/** Crane telescoping. +1 = extend. Default , / . */
	TELESCOPE("work_telescope_out", "work_telescope_in"),
	/** Crane hoist. +1 = hook up. Default ; / / */
	HOIST("work_hoist_up", "work_hoist_down");

	private final String positiveId;
	private final String negativeId;

	WorkAxis(String positiveId, String negativeId) {
		this.positiveId = positiveId;
		this.negativeId = negativeId;
	}

	public String positiveTranslationKey() {
		return "key.constructionaddon." + this.positiveId;
	}

	public String negativeTranslationKey() {
		return "key.constructionaddon." + this.negativeId;
	}

	/** Packs one -1/0/+1 value per axis into 2 bits each. */
	public static int pack(float[] values) {
		int mask = 0;
		for (WorkAxis axis : values()) {
			float v = values[axis.ordinal()];
			if (v > 0.5f) {
				mask |= 1 << (axis.ordinal() * 2);
			} else if (v < -0.5f) {
				mask |= 2 << (axis.ordinal() * 2);
			}
		}
		return mask;
	}

	public static float unpack(int mask, WorkAxis axis) {
		int bits = (mask >> (axis.ordinal() * 2)) & 3;
		return bits == 1 ? 1f : bits == 2 ? -1f : 0f;
	}
}
