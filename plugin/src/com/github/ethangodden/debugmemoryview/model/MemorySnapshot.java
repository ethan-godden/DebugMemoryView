
package com.github.ethangodden.debugmemoryview.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One coherent picture of a suspended debuggee: threads (each a call stack)
 * sharing one heap. Immutable, so snapshots are safe to hand between the
 * extraction job and the UI thread.
 *
 * <p>
 * A {@link Value.Reference} is opaque: only this snapshot's {@link Builder} can
 * mint one, and {@link #resolve} is the only way through one, so how targets
 * are addressed never leaves this file. A reference whose target is absent from
 * the snapshot resolves empty &mdash; that is what "dangling" means.
 */
public final class MemorySnapshot {
	private final String targetId;
	private final List<DisplayableThread> threads;
	private final List<DisplayableStruct> heap; // discovery order
	private final Map<String, DisplayableStruct> heapById;

	private MemorySnapshot(Builder b) {
		this.targetId = b.targetId;
		this.threads = List.copyOf(b.threads);
		this.heap = List.copyOf(b.heap.values());
		this.heapById = Map.copyOf(b.heap);
	}

	/**
	 * @param targetId identifies the debug session this snapshot came from. It is
	 *                 baked into every minted reference token, so resolving a
	 *                 reference against a snapshot of a different target is a clean
	 *                 miss (dangling) rather than a chance JDI-id collision.
	 */
	public static Builder builder(String targetId) {
		return new Builder(targetId);
	}

	/**
	 * @return {@link String} that identifies the debug session. See comment above
	 *         {@link MemorySnapshot#builder(String)} for why its needed
	 */
	public String targetId() {
		return targetId;
	}

	public List<DisplayableThread> threads() {
		return threads;
	}

	public List<DisplayableStruct> heap() {
		return heap;
	}

	/** The only door through a Reference. Empty = dangling. */
	public Optional<DisplayableStruct> resolve(Value.Reference ref) {
		return Optional.ofNullable(heapById.get(ref.target));
	}

	/**
	 * What a variable holds. See {@link DisplayableVariable} for the null
	 * convention.
	 */
	public sealed interface Value {

		/** Self-contained display text: "42", "\"hi\"", "?" for unreadable. */
		record Primitive(String value) implements Value {}

		/**
		 * Opaque handle to a struct; meaningless except via
		 * {@link MemorySnapshot#resolve}.
		 */
		final class Reference implements Value {
			private final String target;

			private Reference(String target) {
				this.target = target;
			}

			@Override
			public boolean equals(Object o) {
				return o instanceof Reference r && target.equals(r.target);
			}

			@Override
			public int hashCode() {
				return target.hashCode();
			}
		}
	}

	/**
	 * One row in a frame or struct. A null {@code value} means the variable holds
	 * null.
	 */
	public record DisplayableVariable(String label, String type, Value value) {}

	/**
	 * Intrinsic-lock state for one struct; null on the vast majority of structs.
	 * {@code heldBy} is the id of the thread currently inside
	 * {@code synchronized(thisObject)}; {@code waiters} are the ids of threads
	 * parked in {@code thisObject.wait()}.
	 */
	public record Monitor(String heldBy, List<String> waiters) {
		public Monitor {
			waiters = List.copyOf(waiters);
		}
	}

	/**
	 * Every heap box: object, array ("[0]"&hellip; labels), string,
	 * statics-of-a-class.
	 */
	public record DisplayableStruct(
			String id,
			String type,
			List<DisplayableVariable> variables,
			boolean explored, // false = unexpanded stub, not "object with no fields"
			int omitted, // rows lost to caps: renders as "+ N more"
			Monitor monitor
	) {
		public DisplayableStruct {
			variables = List.copyOf(variables);
		}
	}

	/**
	 * {@code note != null} marks a body-only frame ("(native method)", &hellip;).
	 */
	public record DisplayableFrame(
			String id,
			String label,
			List<DisplayableVariable> variables,
			String note
	) {
		public DisplayableFrame {
			variables = List.copyOf(variables);
		}
	}

	/**
	 * {@code state} is a frontend-supplied display label ("running", "waiting",
	 * &hellip;); {@code contendedOn} (nullable) is the struct whose monitor this
	 * thread is stuck waiting to acquire.
	 */
	public record DisplayableThread(
			String id,
			String name,
			String state,
			List<DisplayableFrame> frames,
			Value.Reference contendedOn
	) {
		public DisplayableThread {
			frames = List.copyOf(frames);
		}
	}

	/** The single ingestion point &mdash; and the only minter of References. */
	public static final class Builder {
		private final String targetId;
		private final List<DisplayableThread> threads = new ArrayList<>();
		private final Map<String, DisplayableStruct> heap = new LinkedHashMap<>();

		private Builder(String targetId) {
			this.targetId = Objects.requireNonNull(targetId);
		}

		/**
		 * Mint a handle; pure &mdash; a target nobody ever reserves is simply dangling.
		 */
		public Value.Reference reference(String structId) {
			return new Value.Reference(token(structId));
		}

		/**
		 * Stub-first: claims id's place in discovery order; {@link #fill} replaces it.
		 */
		public Builder reserve(String id, String type) {
			heap.putIfAbsent(token(id), new DisplayableStruct(id, type, List.of(), false, 0, null));
			return this;
		}

		/** Replaces the stub (or an earlier fill) for {@code struct.id()}. */
		public Builder fill(DisplayableStruct struct) {
			heap.put(token(struct.id()), struct);
			return this;
		}

		public Builder thread(DisplayableThread thread) {
			threads.add(thread);
			return this;
		}

		public MemorySnapshot build() {
			return new MemorySnapshot(this);
		}

		private String token(String structId) {
			return targetId + "/" + structId;
		}
	}

}
