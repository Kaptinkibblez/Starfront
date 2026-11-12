package com.starwake.process;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A machine's process tank (M8c): holds several chemical species at once (an off-gas outlet with
 * SO2 and CO2, an inlet with water and pine oil), each exposed to pipes as its own pure fluid.
 * Capacity is shared, in kg. Works with Fabric transactions.
 */
public class ChemicalTank extends SnapshotParticipant<Map<String, Long>> implements Storage<FluidVariant> {
	private final Map<String, Long> amounts = new LinkedHashMap<>();
	private final long capacity;
	private final boolean insertable, extractable;
	private final Predicate<String> accepts;
	private final Runnable onChange;

	public ChemicalTank(double capacityKg, boolean insertable, boolean extractable, Predicate<String> accepts, Runnable onChange) {
		this.capacity = Math.round(capacityKg * Chemicals.KG);
		this.insertable = insertable;
		this.extractable = extractable;
		this.accepts = accepts;
		this.onChange = onChange;
	}

	public long total() {
		long t = 0;
		for (long v : amounts.values()) t += v;
		return t;
	}

	public double kg(String species) {
		return amounts.getOrDefault(species, 0L) / (double) Chemicals.KG;
	}

	public double totalKg() {
		return total() / (double) Chemicals.KG;
	}

	public double capacityKg() {
		return capacity / (double) Chemicals.KG;
	}

	public Map<String, Long> contents() {
		return amounts;
	}

	/** The species with the most mass, or null. */
	public String main() {
		String best = null;
		long most = 0;
		for (var e : amounts.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** Machine side: adds kg, returns what didn't fit (kg). */
	public double add(String species, double kg) {
		long want = Math.round(kg * Chemicals.KG);
		long room = Math.max(0, capacity - total());
		long put = Math.min(want, room);
		if (put > 0) {
			amounts.merge(species, put, Long::sum);
			onChange.run();
		}
		return (want - put) / (double) Chemicals.KG;
	}

	/** Machine side: takes up to kg, returns kg taken. */
	public double take(String species, double kg) {
		long have = amounts.getOrDefault(species, 0L);
		long got = Math.min(have, Math.round(kg * Chemicals.KG));
		if (got <= 0) return 0;
		if (have - got <= 0) amounts.remove(species);
		else amounts.put(species, have - got);
		onChange.run();
		return got / (double) Chemicals.KG;
	}

	@Override
	public boolean supportsInsertion() {
		return insertable;
	}

	@Override
	public boolean supportsExtraction() {
		return extractable;
	}

	@Override
	public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
		if (!insertable) return 0;
		String species = Chemicals.speciesOf(resource).orElse(null);
		if (species == null || !accepts.test(species)) return 0;
		long put = Math.min(maxAmount, capacity - total());
		if (put <= 0) return 0;
		updateSnapshots(tx);
		amounts.merge(species, put, Long::sum);
		return put;
	}

	@Override
	public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
		if (!extractable) return 0;
		String species = Chemicals.speciesOf(resource).orElse(null);
		if (species == null) return 0;
		long have = amounts.getOrDefault(species, 0L);
		long got = Math.min(have, maxAmount);
		if (got <= 0) return 0;
		updateSnapshots(tx);
		if (have - got <= 0) amounts.remove(species);
		else amounts.put(species, have - got);
		return got;
	}

	@Override
	public Iterator<StorageView<FluidVariant>> iterator() {
		List<StorageView<FluidVariant>> views = new ArrayList<>();
		for (String species : List.copyOf(amounts.keySet())) {
			FluidVariant variant = Chemicals.variant(species);
			views.add(new StorageView<>() {
				@Override
				public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
					return resource.equals(variant) ? ChemicalTank.this.extract(resource, maxAmount, tx) : 0;
				}

				@Override
				public boolean isResourceBlank() {
					return false;
				}

				@Override
				public FluidVariant getResource() {
					return variant;
				}

				@Override
				public long getAmount() {
					return amounts.getOrDefault(species, 0L);
				}

				@Override
				public long getCapacity() {
					return capacity;
				}
			});
		}
		return views.iterator();
	}

	@Override
	protected Map<String, Long> createSnapshot() {
		return new LinkedHashMap<>(amounts);
	}

	@Override
	protected void readSnapshot(Map<String, Long> snapshot) {
		amounts.clear();
		amounts.putAll(snapshot);
	}

	@Override
	protected void onFinalCommit() {
		onChange.run();
	}

	public void write(ValueOutput output, String key) {
		ValueOutput o = output.child(key);
		amounts.forEach((k, v) -> o.putLong(k, v));
	}

	public void read(ValueInput input, String key) {
		amounts.clear();
		input.child(key).ifPresent(i -> {
			for (var s : Species.all()) {
				long v = i.getLongOr(s.id(), 0);
				if (v > 0) amounts.put(s.id(), v);
			}
		});
	}
}
