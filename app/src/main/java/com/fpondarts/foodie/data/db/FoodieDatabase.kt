package com.fpondarts.foodie.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.fpondarts.foodie.data.db.dao.*
import com.fpondarts.foodie.data.db.entity.*


@Database(
    entities = [User::class
        , Shop::class
        , MenuItem::class
        , Delivery::class
        , Offer::class
        , Order::class
        , OrderItem::class],
    version = 1
)

@TypeConverters(Converters::class)
abstract class FoodieDatabase: RoomDatabase(){

    abstract fun getUserDao() : UserDao
    abstract fun getShopDao() : ShopDao
    abstract fun getMenuItemDao() : MenuItemDao
    abstract fun getDeliveryDao() : DeliveryDao
    abstract fun getOrderDao(): OrderDao
    abstract fun getOrderItemDao(): OrderItemDao

    companion object{
        @Volatile
        private var instance: FoodieDatabase? = null
        private val LOCK = Any()

        operator fun invoke(context: Context) = instance ?: synchronized(LOCK) {
            instance?:buildDatabase(context).also {
                instance = it
            }
        }

        private fun buildDatabase(context:Context) =
            Room.databaseBuilder(
                context.applicationContext,
                FoodieDatabase::class.java,
                "FoodieDatabase.db")
                .build()

    }


}