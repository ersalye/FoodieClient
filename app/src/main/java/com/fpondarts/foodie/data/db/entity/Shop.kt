package com.fpondarts.foodie.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Shop(
    @PrimaryKey
    val id:Int,
    val name: String,
    val rating: Float,
    val photoUrl: String
){
}