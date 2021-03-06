package com.fpondarts.foodie.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.fpondarts.foodie.data.db.Converters
import com.fpondarts.foodie.model.OfferState

@Entity
data class Offer(
    @PrimaryKey
    val id:Long,
    val order_id:Long,
    val delivery_id:Long,
    val state: String,
    val created_at_seconds:Long?,
    val delivery_price:Float,
    val delivery_pay:Float
)