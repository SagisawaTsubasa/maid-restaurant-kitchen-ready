package com.sagisawa.maidrestaurantcooking.cooktask.furnace;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

import java.util.List;

/**
 * 炉式三实类：烧炼→熔炉（574 条中可食者）、烟熏→烟熏炉（241）、高炉→高炉（175）。
 * 营火（campfire_cooking，293 条）不接——本体 ToasterCookTask 已覆盖，缺口另立议题（v5 §二 M3）。
 */
public final class FurnaceRoutingCookTasks {
    private FurnaceRoutingCookTasks() {
    }

    /** 烧炼 → 熔炉。 */
    public static class Smelting extends AbstractFurnaceRoutingCookTask<AbstractCookingRecipe> {
        @Override
        protected Class<? extends AbstractFurnaceBlockEntity> vanillaFurnaceClass() {
            return FurnaceBlockEntity.class;
        }

        @Override
        protected RecipeType<AbstractCookingRecipe> recipeType() {
            @SuppressWarnings("unchecked")
            RecipeType<AbstractCookingRecipe> t = (RecipeType<AbstractCookingRecipe>) (RecipeType<?>) RecipeType.SMELTING;
            return t;
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(Items.FURNACE);
        }
    }

    /** 烟熏 → 烟熏炉。 */
    public static class Smoking extends AbstractFurnaceRoutingCookTask<AbstractCookingRecipe> {
        @Override
        protected Class<? extends AbstractFurnaceBlockEntity> vanillaFurnaceClass() {
            return SmokerBlockEntity.class;
        }

        @Override
        protected RecipeType<AbstractCookingRecipe> recipeType() {
            @SuppressWarnings("unchecked")
            RecipeType<AbstractCookingRecipe> t = (RecipeType<AbstractCookingRecipe>) (RecipeType<?>) RecipeType.SMOKING;
            return t;
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(Items.SMOKER);
        }
    }

    /** 高炉（仅可食产物，矿石冶炼不进点单）。 */
    public static class Blasting extends AbstractFurnaceRoutingCookTask<AbstractCookingRecipe> {
        @Override
        protected Class<? extends AbstractFurnaceBlockEntity> vanillaFurnaceClass() {
            return BlastFurnaceBlockEntity.class;
        }

        @Override
        protected RecipeType<AbstractCookingRecipe> recipeType() {
            @SuppressWarnings("unchecked")
            RecipeType<AbstractCookingRecipe> t = (RecipeType<AbstractCookingRecipe>) (RecipeType<?>) RecipeType.BLASTING;
            return t;
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(Items.BLAST_FURNACE);
        }
    }
}
