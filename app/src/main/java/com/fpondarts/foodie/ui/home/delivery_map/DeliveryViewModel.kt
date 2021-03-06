package com.fpondarts.foodie.ui.home.delivery_map

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.fpondarts.foodie.data.repository.UserRepository
import com.fpondarts.foodie.model.OfferState

class DeliveryViewModel(val repository: UserRepository): ViewModel() {

    val offerState = MutableLiveData<OfferState>().apply {
        value = null
    }

    var deliveryId : Long? = null

    fun postOffer(){
        offerState.postValue(OfferState.WAITING)
    }

    fun cancelOrder(){

    }

    fun keepLooking(){

    }
}