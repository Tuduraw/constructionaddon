package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.Joint;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves a machine's joints into one MODEL-space matrix per OBJ group, each already containing
 * its whole parent chain - exactly the form the base mod's tudursvehiclemod$getCustomPartTransforms()
 * expects. Shared by rendering (client, interpolated channel values) and by work-point location
 * (server, current values), so both always agree. */
public final class JointSolver {

	private JointSolver() {
	}

	@FunctionalInterface
	public interface ChannelSource {
		float value(String channel);
	}

	@FunctionalInterface
	public interface SpinSource {
		float angle(Joint joint);
	}

	public static Map<String, Matrix4f> solve(List<Joint> joints, ChannelSource channels, SpinSource spin) {
		if (joints.isEmpty()) {
			return Map.of();
		}
		Map<String, Joint> byPart = new HashMap<>();
		for (Joint joint : joints) {
			byPart.put(joint.part(), joint);
		}
		Map<String, Matrix4f> resolved = new HashMap<>();
		for (Joint joint : joints) {
			resolve(joint, byPart, resolved, new HashSet<>(), channels, spin);
		}
		return resolved;
	}

	private static Matrix4f resolve(Joint joint, Map<String, Joint> byPart, Map<String, Matrix4f> resolved,
			Set<String> visiting, ChannelSource channels, SpinSource spin) {
		Matrix4f done = resolved.get(joint.part());
		if (done != null) {
			return done;
		}
		visiting.add(joint.part());

		Matrix4f parentMatrix = new Matrix4f();
		if (joint.parent().isPresent()) {
			Joint parent = byPart.get(joint.parent().get());
			// A cycle (a -> b -> a) or a missing parent simply falls back to the identity.
			if (parent != null && !visiting.contains(parent.part())) {
				Matrix4f full = resolve(parent, byPart, resolved, visiting, channels, spin);
				if (joint.inheritRotation()) {
					parentMatrix = new Matrix4f(full);
				} else {
					// Only where the parent carries this joint's pivot - not how it turns it.
					Vector3f moved = full.transformPosition(new Vector3f(joint.pivotX(), joint.pivotY(), joint.pivotZ()));
					parentMatrix = new Matrix4f().translation(
							moved.x - joint.pivotX(), moved.y - joint.pivotY(), moved.z - joint.pivotZ());
				}
			}
		}

		Vector3f axis = new Vector3f(joint.axisX(), joint.axisY(), joint.axisZ());
		if (axis.lengthSquared() < 1.0e-8f) {
			axis.set(1f, 0f, 0f);
		}
		axis.normalize();

		Matrix4f own = new Matrix4f().translation(joint.pivotX(), joint.pivotY(), joint.pivotZ());
		switch (joint.mode()) {
			case ROTATE -> own.rotate((float) Math.toRadians(clampedValue(joint, channels)), axis);
			case SLIDE -> {
				float distance = clampedValue(joint, channels);
				own.translate(axis.x * distance, axis.y * distance, axis.z * distance);
			}
			case SCALE -> {
				// Scaling along one axis: S = I + (s - 1) * a * aT. Never quite zero, so the normal
				// matrix the renderer derives from it stays finite.
				float s = Math.max(0.02f, clampedValue(joint, channels));
				float k = s - 1f;
				Matrix4f scale = new Matrix4f(
						1f + k * axis.x * axis.x, k * axis.x * axis.y, k * axis.x * axis.z, 0f,
						k * axis.y * axis.x, 1f + k * axis.y * axis.y, k * axis.y * axis.z, 0f,
						k * axis.z * axis.x, k * axis.z * axis.y, 1f + k * axis.z * axis.z, 0f,
						0f, 0f, 0f, 1f);
				own.mul(scale);
			}
			case SPIN -> own.rotate((float) Math.toRadians(spin.angle(joint) + joint.offset()), axis);
		}
		own.translate(-joint.pivotX(), -joint.pivotY(), -joint.pivotZ());

		Matrix4f result = parentMatrix.mul(own);
		resolved.put(joint.part(), result);
		visiting.remove(joint.part());
		return result;
	}

	private static float clampedValue(Joint joint, ChannelSource channels) {
		float value = joint.channel().map(channels::value).orElse(0f);
		float raw = joint.offset() + joint.factor() * value;
		return MathHelper.clamp(raw, Math.min(joint.min(), joint.max()), Math.max(joint.min(), joint.max()));
	}
}
