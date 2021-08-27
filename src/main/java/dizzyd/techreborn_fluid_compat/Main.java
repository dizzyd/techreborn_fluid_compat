package dizzyd.techreborn_fluid_compat;

import alexiil.mc.lib.attributes.AttributeList;
import alexiil.mc.lib.attributes.AttributeSourceType;
import alexiil.mc.lib.attributes.CombinableAttribute;
import alexiil.mc.lib.attributes.ListenerRemovalToken;
import alexiil.mc.lib.attributes.ListenerToken;
import alexiil.mc.lib.attributes.Simulation;
import alexiil.mc.lib.attributes.fluid.FixedFluidInv;
import alexiil.mc.lib.attributes.fluid.FluidAttributes;
import alexiil.mc.lib.attributes.fluid.FluidInvTankChangeListener;
import alexiil.mc.lib.attributes.fluid.FluidVolumeUtil;
import alexiil.mc.lib.attributes.fluid.GroupedFluidInv;
import alexiil.mc.lib.attributes.fluid.amount.FluidAmount;
import alexiil.mc.lib.attributes.fluid.filter.FluidFilter;
import alexiil.mc.lib.attributes.fluid.volume.FluidKey;
import alexiil.mc.lib.attributes.fluid.volume.FluidKeys;
import alexiil.mc.lib.attributes.fluid.volume.FluidVolume;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.minecraft.fluid.Fluid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reborncore.common.blockentity.FluidConfiguration;
import reborncore.common.blockentity.FluidConfiguration.ExtractConfig;
import reborncore.common.blockentity.MachineBaseBlockEntity;
import reborncore.common.fluid.FluidValue;
import reborncore.common.fluid.container.FluidInstance;
import reborncore.common.util.Tank;

// This implementation is taken from https://github.com/AlexIIL/LibBlockAttributes/blob/0.8.x-1.16.x/src/main/java/alexiil/mc/lib/attributes/fluid/compat/mod/reborncore/RebornFluidCompat.java
// under the Mozilla Public License. It's my intention to also submit a PR to the origin in hopes this mod becomes
// unnecessary. :)

public class Main implements ModInitializer {
	private static final Logger LOGGER = LogManager.getLogger();

	private static final WeakHashMap<BlockPos, RebornFluidTankWrapper> wrapperCache = new WeakHashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing techreborn fluid compatability");
		FluidAttributes.forEachInv(Main::appendAdder);
	}

	private static <T> void appendAdder(CombinableAttribute<T> attribute) {
		LOGGER.debug("Appending techreborn adder for " + attribute.toString());
		attribute.putBlockEntityClassAdder(
			AttributeSourceType.COMPAT_WRAPPER, MachineBaseBlockEntity.class, true, Main::addWrapper
		);
	}

	static public <T> void addWrapper(MachineBaseBlockEntity machine, AttributeList<T> list) {
		// Check cache first; if we have a tank wrapper w/ same pointer at this position use it
		RebornFluidTankWrapper cachedWrapper = wrapperCache.get(machine.getPos());
		if (cachedWrapper != null && cachedWrapper.base == machine) {
			list.offer(cachedWrapper);
			return;
		}

		LOGGER.debug("Adding wrapper for " + machine.getPos() + " from target: " + list.getTargetSide());
		Direction dir = list.getSearchDirection();
		if (dir == null) {
			LOGGER.debug("No search direction at:" + machine.getPos());
			return;
		}
		Direction side = dir.getOpposite();

		Tank tank = machine.getTank();
		if (tank == null) {
			LOGGER.debug("No tank available at: " + machine.getPos());
			return;
		}

		if (tank.getCapacity() <= 0) {
			LOGGER.debug("Empty tank at: " + machine.getPos());
			return;
		}
		LOGGER.debug("Offering " + tank.getFluid() + " from " + machine.getPos());
		RebornFluidTankWrapper w = new RebornFluidTankWrapper(side, machine, tank);
		wrapperCache.put(machine.getPos(), w);
		list.offer(w);
	}

	static final class RebornFluidTankWrapper implements GroupedFluidInv, FixedFluidInv {
		private final Direction side;
		final MachineBaseBlockEntity base;
		private final Tank tank;

		RebornFluidTankWrapper(Direction side, MachineBaseBlockEntity base, Tank tank) {
			this.side = side;
			this.base = base;
			this.tank = tank;
		}

		// FluidInsertable

		@Override
		public FluidVolume attemptInsertion(FluidVolume fluid, Simulation simulation) {
			// Tech Reborn (as of this writing, for minecraft 1.17), represent fluid values in droplets (aka 1/81000 buckets)
			// N.B. we use raw values to reduce number of object copies that would happen by calling FluidValue.min()
			long maxDroplets = base.fluidTransferAmount().getRawValue();
			if (maxDroplets <= 0) {
				return fluid;
			}

			// TODO: Filters!
			// (Although that would need RebornCore to add a filtering method...
			Fluid rawFluid = fluid.getFluidKey().getRawFluid();
			if (rawFluid == null) {
				return fluid;
			}

			FluidInstance fi = tank.getFluidInstance();
			long tankAmountDroplets = fi.getAmount().getRawValue();
			long availDroplets = tank.getCapacity() - tankAmountDroplets;
			if (availDroplets <= 0) {
				return fluid;
			}

			if (tankAmountDroplets > 0 && fi.getFluid() != rawFluid) {
				return fluid;
			}

			FluidConfiguration cfg = base.fluidConfiguration;
			ExtractConfig ioConfig = cfg != null ? cfg.getSideDetail(side).getIoConfig() : ExtractConfig.ALL;
			if (ioConfig.isInsert()) {
				// Convert offered amount of fluid to droplets; then take the smaller number of droplets that
				// will fit or are offered
				long offeredAmount = fluid.getAmount_F().asLong(FluidConstants.BUCKET, RoundingMode.DOWN);
				offeredAmount = Math.min(offeredAmount, Math.min(availDroplets, maxDroplets));

				FluidVolume ret = fluid.copy();
				FluidVolume offered = ret.split(FluidAmount.of(offeredAmount, FluidConstants.BUCKET));

				FluidAmount mul = offered.getAmount_F().checkedMul(FluidConstants.BUCKET);
				assert mul.whole == offeredAmount : "Bad split! (whole)";
				assert mul.numerator == 0 : "Bad split! (numerator)";

				if (simulation.isAction()) {
					FluidValue val = FluidValue.fromRaw(offeredAmount);
					if (tankAmountDroplets <= 0) {
						fi = new FluidInstance(rawFluid, val);
					} else {
						fi.addAmount(val);
					}
					tank.setFluid(fi);
				}
				return ret;
			}
			return fluid;
		}

		// FluidExtractable

		@Override
		public FluidVolume attemptExtraction(FluidFilter filter, FluidAmount maxAmount, Simulation simulation) {
			// Tech Reborn (as of this writing, for minecraft 1.17), represent fluid values in droplets (aka 1/81000 buckets)
			// N.B. we use raw values to reduce number of object copies that would happen by calling FluidValue.min()
			long maxDroplets = base.fluidTransferAmount().getRawValue(); //
			if (maxDroplets <= 0) {
				return FluidVolumeUtil.EMPTY;
			}

			FluidInstance fi = tank.getFluidInstance();
			long tankAmountDroplets = fi.getAmount().getRawValue();
			if (tankAmountDroplets <= 0) {
				return FluidVolumeUtil.EMPTY;
			}

			long available = Math.min(tankAmountDroplets, maxDroplets);
			if (available <= 0) {
				return FluidVolumeUtil.EMPTY;
			}

			FluidKey key = FluidKeys.get(tank.getFluid());
			if (!filter.matches(key)) {
				return FluidVolumeUtil.EMPTY;
			}

			FluidConfiguration cfg = base.fluidConfiguration;
			ExtractConfig ioConfig = cfg != null ? cfg.getSideDetail(side).getIoConfig() : ExtractConfig.ALL;
			if (ioConfig.isExtact()) {
				// Convert the number of droplets into a FluidAmount (in which 1 == 1 bucket) by providing
				// the constant # of droplets in a bucket
				FluidVolume volume = key.withAmount(FluidAmount.of(available, FluidConstants.BUCKET));
				if (simulation.isAction()) {
					fi.subtractAmount(FluidValue.fromRaw(available));
					tank.setFluid(fi);
				}
				return volume;
			}
			return FluidVolumeUtil.EMPTY;
		}

		// GroupedFluidInvView

		@Override
		public Set<FluidKey> getStoredFluids() {
			FluidInstance fi = tank.getFluidInstance();
			long amount = fi.getAmount().getRawValue();
			if (amount <= 0) {
				return Collections.emptySet();
			} else {
				return Collections.singleton(FluidKeys.get(fi.getFluid()));
			}
		}

		@Override
		public FluidInvStatistic getStatistics(FluidFilter filter) {
			FluidInstance fi = tank.getFluidInstance();
			long amount = fi.getAmount().getRawValue();
			if (amount <= 0) {
				FluidAmount capacity = FluidAmount.of(tank.getCapacity(), FluidConstants.BUCKET);
				return new FluidInvStatistic(filter, FluidAmount.ZERO, capacity, capacity);
			} else {
				FluidKey key = FluidKeys.get(fi.getFluid());
				if (filter.matches(key)) {
					FluidAmount fa = FluidAmount.of(amount, FluidConstants.BUCKET);
					FluidAmount capacity = FluidAmount.of(tank.getCapacity(), FluidConstants.BUCKET);
					return new FluidInvStatistic(filter, fa, capacity, capacity);
				} else {
					FluidAmount fa = FluidAmount.of(amount, FluidConstants.BUCKET);
					FluidAmount capacity = FluidAmount.of(tank.getCapacity(), FluidConstants.BUCKET);
					return new FluidInvStatistic(filter, FluidAmount.ZERO, FluidAmount.ZERO, capacity);
				}
			}
		}

		// FixedFluidInvView

		@Override
		public int getTankCount() {
			return 1;
		}

		@Override
		public FluidVolume getInvFluid(int t) {
			FluidInstance fi = tank.getFluidInstance();
			long amount = fi.getAmount().getRawValue();
			if (amount <= 0) {
				return FluidVolumeUtil.EMPTY;
			} else {
				return FluidKeys.get(fi.getFluid()).withAmount(FluidAmount.of(amount, FluidConstants.BUCKET));
			}
		}

		@Override
		public ListenerToken addListener(FluidInvTankChangeListener listener, ListenerRemovalToken removalToken) {
			return null;
		}

		@Override
		public boolean isFluidValidForTank(int tank, FluidKey fluid) {
			return true; // Unfortunately we don't have filters :(
		}

		// FixedFluidInv

		@Override
		public boolean setInvFluid(int t, FluidVolume to, Simulation simulation) {
			if (to.isEmpty()) {
				if (simulation.isAction()) {
					tank.setFluid(new FluidInstance());
				}
				return true;
			}
			FluidAmount mul = to.getAmount_F().saturatedMul(FluidConstants.BUCKET);
			if (mul.numerator != 0) {
				return false;
			}
			if (mul.whole > Integer.MAX_VALUE) {
				return false;
			}
			Fluid raw = to.getRawFluid();
			if (raw == null) {
				return false;
			}
			if (simulation.isAction()) {
				FluidInstance fi = tank.getFluidInstance();
				FluidValue value = FluidValue.fromRaw((int) mul.whole);
				if (fi.getFluid().equals(raw)) {
					// So we keep the tag
					fi.setAmount(value);
				} else {
					tank.setFluid(new FluidInstance(raw, value));
				}
			}
			return true;
		}

		@Override
		public GroupedFluidInv getGroupedInv() {
			return this;
		}
	}
}






