package me.branchpanic.mods.stockpile.blockentity

import java.text.NumberFormat
import java.util.UUID

import me.branchpanic.mods.stockpile.inventory.MassItemInventory
import net.fabricmc.fabric.api.block.entity.BlockEntityClientSerializable
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.block.entity.{BlockEntity, BlockEntityType}
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.SidedInventory
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.sound.{SoundCategory, SoundEvents}
import net.minecraft.text.TranslatableTextComponent
import net.minecraft.util.math.Direction
import me.branchpanic.mods.stockpile.inventory.MassItemInventory

import scala.collection.JavaConverters._
import scala.language.implicitConversions

object StockpileBarrelBlockEntity {
  val Type: BlockEntityType[StockpileBarrelBlockEntity] =
    BlockEntityType.Builder
      .create[StockpileBarrelBlockEntity](() => new StockpileBarrelBlockEntity)
      .build(null)

  val DoubleClickPeriodMs = 500
}

class StockpileBarrelBlockEntity
    extends BlockEntity(StockpileBarrelBlockEntity.Type)
    with BlockEntityPersistence
    with BlockEntityClientSerializable
    with SidedInventory {

  var inventory = new MassItemInventory(onChanged = () => markDirty())

  private var playerRightClickTimers: Map[UUID, Long] = Map.empty

  def handleLeftClick(player: PlayerEntity): Unit = {
    val extractedStack = inventory.takeInvStack(
      MassItemInventory.OutputSlotIndex,
      if (player.isSneaking) inventory.stackSize else 1)

    if (!extractedStack.isEmpty) {
      player.inventory.insertStack(extractedStack)
      world.spawnEntity(
        new ItemEntity(world, player.x, player.y, player.z, extractedStack))
      world.playSound(null,
                      pos,
                      SoundEvents.ENTITY_ITEM_PICKUP,
                      SoundCategory.BLOCK,
                      0.1f,
                      0.7f)
      displayContentInfo(player)
    }
  }

  def handleRightClick(player: PlayerEntity): Unit = {
    playerRightClickTimers = playerRightClickTimers
      .filter {
        case (_, time) =>
          System.currentTimeMillis() - time <= StockpileBarrelBlockEntity.DoubleClickPeriodMs
      }

    if (player.isSneaking) {
      toggleEmptyBehavior(player)
      return
    }

    if (playerInteractedTwice(player) && !inventory.isAcceptingNewStackType) {
      insertAllStacksFromInventory(player)
    } else {
      recordPlayerInteraction(player)
      insertActiveStack(player)
    }

    displayContentInfo(player)
  }

  def toggleEmptyBehavior(player: PlayerEntity): Unit = {
    inventory.allowNewStackWhenEmpty = !inventory.allowNewStackWhenEmpty

    if (inventory.isInvEmpty && inventory.allowNewStackWhenEmpty) {
      inventory.clear()
    }

    if (inventory.allowNewStackWhenEmpty) {
      world.playSound(null,
                      pos,
                      SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_OFF,
                      SoundCategory.BLOCK,
                      0.1f,
                      0.9f)
      player.addChatMessage(
        new TranslatableTextComponent("stockpile.barrel.just_unlocked"),
        true)
    } else {
      world.playSound(null,
                      pos,
                      SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_ON,
                      SoundCategory.BLOCK,
                      0.1f,
                      0.9f)
      player.addChatMessage(
        new TranslatableTextComponent("stockpile.barrel.just_locked"),
        true)
    }

    markDirty()
  }

  def recordPlayerInteraction(player: PlayerEntity): Unit = {
    playerRightClickTimers += (player.getUuid -> System.currentTimeMillis())
  }

  def playerInteractedTwice(player: PlayerEntity): Boolean =
    playerRightClickTimers.contains(player.getUuid)

  /**
    * Inserts the stack a player is holding, updating it in-place.
    *
    * @param player Player to insert held stack from.
    */
  def insertActiveStack(player: PlayerEntity): Unit = {
    val activeHand = player.getActiveHand
    val activeStackBeforeInsert = player.getStackInHand(activeHand)
    val resultingStack = inventory.insertStack(activeStackBeforeInsert)
    player.setStackInHand(activeHand, resultingStack)
  }

  /**
    * Inserts all possible stacks from a player's inventory, updating it in-place.
    *
    * @param player Player to insert all stacks from.
    */
  def insertAllStacksFromInventory(player: PlayerEntity): Unit = {
    player.inventory.main.replaceAll(inventory.insertStack)
    player.inventory.offHand.replaceAll(inventory.insertStack)
    player.inventory.markDirty()
  }

  /**
    * Displays information about this barrel to a specified player.
    *
    * @param player Player to display information to.
    */
  def displayContentInfo(player: PlayerEntity): Unit = {
    if (inventory.isInvEmpty) {
      player.addChatMessage(
        new TranslatableTextComponent("stockpile.barrel.empty"),
        true)
    } else {
      val formatter = NumberFormat.getInstance()

      player.addChatMessage(
        new TranslatableTextComponent(
          "stockpile.barrel.contents_world",
          formatter.format(inventory.amountStored),
          formatter.format(inventory.maxStacks * inventory.stackSize),
          inventory.stackType.getDisplayName,
          formatter.format(inventory.amountStored / inventory.stackSize),
          formatter.format(inventory.maxStacks)
        ),
        true
      )
    }
  }

  override def toString: String = s"barrelBlockEntity{inventory=$inventory,}"

  override def canPlayerUseInv(playerEntity: PlayerEntity): Boolean =
    playerEntity.squaredDistanceTo(pos) < 12 * 12

  override def markDirty(): Unit = {
    super.markDirty()
    world.updateListeners(pos,
                          world.getBlockState(pos),
                          world.getBlockState(pos),
                          3)
  }

  override def fromClientTag(compoundTag: CompoundTag): Unit = {
    if (compoundTag.containsKey("PersistentData", NbtType.COMPOUND)) {
      loadFromTag(compoundTag.getCompound("PersistentData"))
    }
  }
  override def toClientTag(compoundTag: CompoundTag): CompoundTag = {
    compoundTag.put("PersistentData", saveToTag())
    compoundTag
  }

  // Delegation of SidedInventory to MassItemInventory

  override def getInvSize: Int = inventory.getInvSize

  override def isInvEmpty: Boolean = inventory.isInvEmpty

  override def getInvStack(i: Int): ItemStack = inventory.getInvStack(i)

  override def takeInvStack(i: Int, i1: Int): ItemStack =
    inventory.takeInvStack(i, i1)

  override def isValidInvStack(int_1: Int, itemStack_1: ItemStack): Boolean =
    inventory.isValidInvStack(int_1, itemStack_1)

  override def removeInvStack(i: Int): ItemStack = inventory.removeInvStack(i)

  override def setInvStack(i: Int, itemStack: ItemStack): Unit =
    inventory.setInvStack(i, itemStack)

  override def getInvAvailableSlots(direction: Direction): Array[Int] =
    inventory.getInvAvailableSlots(direction)

  override def canInsertInvStack(i: Int,
                                 itemStack: ItemStack,
                                 direction: Direction): Boolean =
    inventory.canInsertInvStack(i, itemStack, direction)

  override def canExtractInvStack(i: Int,
                                  itemStack: ItemStack,
                                  direction: Direction): Boolean =
    inventory.canExtractInvStack(i, itemStack, direction)

  override def clear(): Unit = inventory.clear()

  override def saveToTag(): CompoundTag = {
    val tag = new CompoundTag()
    tag.put("inventory", inventory.saveToTag())
    tag
  }

  override def loadFromTag(tag: CompoundTag): Unit = {
    if (tag.containsKey("inventory")) {
      inventory.loadFromTag(tag.getCompound("inventory"))
    }
  }
}