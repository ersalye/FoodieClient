package com.fpondarts.foodie.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fpondarts.foodie.data.db.entity.Delivery

@Dao
interface DeliveryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(deliveries: Collection<Delivery>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(delivery:Delivery)

    @Query("SELECT * FROM DELIVERY WHERE available=:available")
    fun getByAvailability(available:Boolean):LiveData<List<Delivery>>

    @Query("Select * From Delivery Where product_id=:product_id")
    fun getDelivery(id:Long):LiveData<Delivery>




}