package com.fpondarts.foodie.ui.home.shop_menu

import androidx.lifecycle.*
import com.fpondarts.foodie.data.db.entity.MenuItem
import com.fpondarts.foodie.data.repository.UserRepository
import com.fpondarts.foodie.ui.auth.AuthListener
import com.fpondarts.foodie.util.exception.FoodieApiException

class ShopViewModel (val repository: UserRepository ) : ViewModel() {

    var liveMenu = MutableLiveData<ArrayList<MenuItem>>().apply {
        value = ArrayList()
    }


    var listener : AuthListener? = null

    val menu = ArrayList<MenuItem>()

    fun setShop(shopId: Long){
        repository.newOrder(shopId)
    }

    fun getMenu(shopId: Long): LiveData<List<MenuItem>>{
        var liveData: LiveData<List<MenuItem>>? = null
        try {
            return repository.getMenu(shopId)
        } catch (e: FoodieApiException){
            listener!!.onFailure(e.message!!)
        }
        return liveData!!
    }


}
