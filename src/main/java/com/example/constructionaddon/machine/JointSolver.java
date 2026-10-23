package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.Crawler;
import com.example.constructionaddon.asset.Joint;
import com.example.constructionaddon.asset.WorkPoint;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves a machine's joints (and its own crawlers' links) into one MODEL-space matrix per OBJ
 * group, each already containing its whole parent chain - exactly the form the base mod's
 * tudursvehiclemod$getCustomPartTransforms() expects. Shared by rendering (client, interpolated
 * values) and by work-point location (server, current values), so both always agree. */
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

	/** Belt travel (blocks) of the base mod's crawler track with this part name. */
	@FunctionalInterface
	public interface BeltSource {
		float travel(String belt);
	}

	public static Map<String, Matrix4f> solve(List<Joint> joints, List<Crawler> crawlers, ChannelSource channels,
			SpinSource spin, BeltSource belts) {
		if (joints.isEmpty() && crawlers.isEmpty()) {
			return Map.of();
		}
		Context context = new Context(channels, spin, belts);
		for (Joint joint : joints) {
			context.byPart.put(joint.part(), joint);
		}
		for (Joint joint : joints) {
			context.resolve(joint);
		}
		for (Crawler crawler : crawlers) {
			solveCrawler(crawler, context);
		}
		return context.resolved;
	}

	private static final class Context {
		final Map<String, Joint> byPart = new HashMap<>();
		final Map<String, Matrix4f> resolved = new HashMap<>();
		final Set<String> visiting = new HashSet<>();
		final ChannelSource channels;
		final SpinSource spin;
		final BeltSource belts;

		Context(ChannelSource channels, SpinSource spin, BeltSource belts) {
			this.channels = channels;
			this.spin = spin;
			this.belts = belts;
		}

		/** The current matrix of a part: its joint's, or the identity for a part without one (or one
		 * still being resolved - a cycle). */
		Matrix4f matrixOf(String part) {
			Joint joint = this.byPart.get(part);
			if (joint == null || this.visiting.contains(part)) {
				Matrix4f done = this.resolved.get(part);
				return done != null ? done : new Matrix4f();
			}
			return this.resolve(joint);
		}

		/** Where a point carried by a part is now, in model coordinates. */
		Vector3f pointNow(WorkPoint point) {
			Vector3f p = new Vector3f(point.x(), point.y(), point.z());
			return point.part().isPresent() ? this.matrixOf(point.part().get()).transformPosition(p) : p;
		}

		Matrix4f resolve(Joint joint) {
			Matrix4f done = this.resolved.get(joint.part());
			if (done != null) {
				return done;
			}
			this.visiting.add(joint.part());

			Matrix4f parentMatrix = new Matrix4f();
			if (joint.parent().isPresent()) {
				Joint parent = this.byPart.get(joint.parent().get());
				// A cycle (a -> b -> a) or a missing parent simply falls back to the identity.
				if (parent != null && !this.visiting.contains(parent.part())) {
					Matrix4f full = this.resolve(parent);
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
				case ROTATE -> own.rotate((float) Math.toRadians(clampedValue(joint, this.channels)), axis);
				case SLIDE -> {
					float distance = clampedValue(joint, this.channels);
					own.translate(axis.x * distance, axis.y * distance, axis.z * distance);
				}
				case SCALE -> {
					// Scaling along one axis: S = I + (s - 1) * a * aT. Never quite zero, so the normal
					// matrix the renderer derives from it stays finite.
					float s = Math.max(0.02f, clampedValue(joint, this.channels));
					float k = s - 1f;
					Matrix4f scale = new Matrix4f(
							1f + k * axis.x * axis.x, k * axis.x * axis.y, k * axis.x * axis.z, 0f,
							k * axis.y * axis.x, 1f + k * axis.y * axis.y, k * axis.y * axis.z, 0f,
							k * axis.z * axis.x, k * axis.z * axis.y, 1f + k * axis.z * axis.z, 0f,
							0f, 0f, 0f, 1f);
					own.mul(scale);
				}
				case SPIN -> own.rotate((float) Math.toRadians(this.spin.angle(joint) + joint.offset()), axis);
				case AIM -> own.rotate(this.aimAngle(joint, parentMatrix, axis), axis);
				case REACH -> {
					float distance = this.reachDistance(joint);
					own.translate(axis.x * distance, axis.y * distance, axis.z * distance);
				}
				case BELT -> {
					float travel = joint.belt().map(this.belts::travel).orElse(0f);
					own.rotate((float) Math.toRadians(joint.factor() * travel + joint.offset()), axis);
				}
				case SCISSOR -> {
					float sine = MathHelper.clamp(clampedValue(joint, this.channels), -1f, 1f);
					float rest = MathHelper.clamp(joint.offset(), -1f, 1f);
					own.rotate((float) (Math.asin(sine) - Math.asin(rest)), axis);
				}
				case SHOW -> {
					// Hidden: shrunk evenly into the pivot (uniform, so the normals stay well defined).
					if (clampedValue(joint, this.channels) < 0.999f) {
						own.scale(1.0e-3f);
					}
				}
			}
			own.translate(-joint.pivotX(), -joint.pivotY(), -joint.pivotZ());

			Matrix4f result = parentMatrix.mul(own);
			this.resolved.put(joint.part(), result);
			this.visiting.remove(joint.part());
			return result;
		}

		/** Radians about the axis that turn the rest direction (pivot -> target at rest) onto the current
		 * one, both seen in the parent's frame. */
		private float aimAngle(Joint joint, Matrix4f parentMatrix, Vector3f axis) {
			if (joint.target().isEmpty()) {
				return (float) Math.toRadians(joint.offset());
			}
			WorkPoint target = joint.target().get();
			Vector3f pivot = new Vector3f(joint.pivotX(), joint.pivotY(), joint.pivotZ());
			Vector3f now = new Matrix4f(parentMatrix).invert().transformPosition(this.pointNow(target)).sub(pivot);
			Vector3f rest = new Vector3f(target.x(), target.y(), target.z()).sub(pivot);
			now.sub(new Vector3f(axis).mul(now.dot(axis)));
			rest.sub(new Vector3f(axis).mul(rest.dot(axis)));
			if (now.lengthSquared() < 1.0e-10f || rest.lengthSquared() < 1.0e-10f) {
				return (float) Math.toRadians(joint.offset());
			}
			float angle = (float) Math.atan2(new Vector3f(rest).cross(now).dot(axis), rest.dot(now));
			return angle + (float) Math.toRadians(joint.offset());
		}

		/** How far the anchor -> target distance has grown since the rest pose, x factor + offset. */
		private float reachDistance(Joint joint) {
			if (joint.target().isEmpty() || joint.anchor().isEmpty()) {
				return joint.offset();
			}
			WorkPoint target = joint.target().get();
			WorkPoint anchor = joint.anchor().get();
			float now = this.pointNow(target).distance(this.pointNow(anchor));
			float rest = new Vector3f(target.x(), target.y(), target.z()).distance(anchor.x(), anchor.y(), anchor.z());
			return (now - rest) * joint.factor() + joint.offset();
		}
	}

	/** One matrix per link: from where the link is modelled (its place at belt travel 0) to its place
	 * at the current travel, on top of the parent's joint. Same placement rule as the base mod's own
	 * crawler tracks, so both kinds look and move alike. */
	private static void solveCrawler(Crawler crawler, Context context) {
		List<Crawler.PathPoint> path = crawler.path();
		int n = path.size();
		if (n < 2) {
			return;
		}
		double[] cumulative = new double[n + 1];
		for (int i = 0; i < n; i++) {
			Crawler.PathPoint a = path.get(i);
			Crawler.PathPoint b = path.get((i + 1) % n);
			cumulative[i + 1] = cumulative[i] + Math.hypot(b.y() - a.y(), b.z() - a.z());
		}
		double total = cumulative[n];
		if (total < 1.0e-6) {
			return;
		}
		float spacing = Math.max(0.05f, crawler.linkSpacing());
		int count = Math.max(1, Math.round((float) (total / spacing)));
		float travel = context.belts.travel(crawler.belt());
		Matrix4f parent = crawler.parent().map(context::matrixOf).orElseGet(Matrix4f::new);
		for (int i = 0; i < count; i++) {
			double[] rest = place(path, cumulative, total, i * spacing);
			double[] now = place(path, cumulative, total, i * spacing + travel);
			Matrix4f m = new Matrix4f(parent)
					.translate(0f, (float) now[0], (float) now[1])
					.rotateX((float) Math.toRadians(now[2] - rest[2]))
					.translate(0f, (float) -rest[0], (float) -rest[1]);
			context.resolved.put(crawler.linkPrefix() + i, m);
		}
	}

	/** {y, z, angle degrees} at a distance along the closed path. */
	private static double[] place(List<Crawler.PathPoint> path, double[] cumulative, double total, double raw) {
		int n = path.size();
		double distance = ((raw % total) + total) % total;
		int segment = 0;
		while (segment < n - 1 && cumulative[segment + 1] < distance) {
			segment++;
		}
		Crawler.PathPoint a = path.get(segment);
		Crawler.PathPoint b = path.get((segment + 1) % n);
		double length = cumulative[segment + 1] - cumulative[segment];
		double t = length > 1.0e-9 ? (distance - cumulative[segment]) / length : 0.0;
		return new double[] {
				a.y() + (b.y() - a.y()) * t,
				a.z() + (b.z() - a.z()) * t,
				Math.toDegrees(Math.atan2(b.z() - a.z(), b.y() - a.y()))};
	}

	private static float clampedValue(Joint joint, ChannelSource channels) {
		float value = joint.channel().map(channels::value).orElse(0f);
		float raw = joint.offset() + joint.factor() * value;
		return MathHelper.clamp(raw, Math.min(joint.min(), joint.max()), Math.max(joint.min(), joint.max()));
	}
}
