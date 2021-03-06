package com.fpondarts.foodie.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fpondarts.foodie.data.db.FoodieDatabase
import com.fpondarts.foodie.network.FoodieApi
import com.fpondarts.foodie.network.SafeApiRequest
import com.fpondarts.foodie.network.response.SignInResponse
import com.fpondarts.foodie.util.Coroutines
import com.fpondarts.foodie.util.exception.FoodieApiException
import com.google.android.gms.common.api.ApiException
import android.util.Log
import com.fpondarts.foodie.data.db.entity.*
import com.fpondarts.foodie.data.parser.RoutesParser
import com.fpondarts.foodie.data.repository.interfaces.OrderRepository
import com.fpondarts.foodie.data.repository.interfaces.RepositoryInterface
import com.fpondarts.foodie.data.repository.interfaces.ShopRepository
import com.fpondarts.foodie.model.*
import com.fpondarts.foodie.model.OrderItem
import com.fpondarts.foodie.network.DirectionsApi
import com.fpondarts.foodie.network.FcmApi
import com.fpondarts.foodie.network.fcm_data.FcmMessageData
import com.fpondarts.foodie.network.request.*
import com.fpondarts.foodie.network.response.PricingResponse
import com.fpondarts.foodie.network.response.SuccessResponse
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.database.DatabaseReference
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import java.lang.Exception

class UserRepository(
    private val api: FoodieApi,
    private val directionsApi: DirectionsApi,
    private val fcmApi: FcmApi,
    private val db : FoodieDatabase
):SafeApiRequest()
    ,PositionUpdater
    ,RepositoryInterface
    {

    val currentUser = MutableLiveData<User>().apply {
        value = null
    }

    var token:String? = null
    var userId:Long? = null

    var currentOrder: OrderModel? = null

    var currentOfferId = MutableLiveData<Long>().apply {
        value = -1
    }

    var observedOffer : MutableLiveData<Offer>? = null

    var observedFavourOffer: MutableLiveData<FavourOffer>? = null

    val apiError = MutableLiveData<FoodieApiException>().apply {
        value = null
    }

    val isWorking = MutableLiveData<Boolean>().apply {
        value = false
    }

    val make_favours = MutableLiveData<Boolean>().apply{
        value = false
    }

    var current_order:Long = -1

    val availableDeliveries = MutableLiveData<List<User>>().apply {
        value = ArrayList<User>()
    }


    val SHOP_PAGE_SIZE = 3;

    fun refreshUser(){
        token?.let{
            initUser(this.token!!,this.userId!!)
        }
    }

    suspend fun foodieSignIn(email: String, password:String?, fbToken:String):SignInResponse{
        return apiRequest { api.signIn(email,password, fbToken) }
    }

    fun initUser(token: String, id:Long){
        if (token==null || id == null){
            return
        }
        this.token = token
        this.userId = id
        Coroutines.io{
            try{
                db.getOrderDao().nukeTable()
                val user = apiRequest{ api.getUserById(token,id) }
                currentUser.postValue(user)
                if (user.state == "working"){
                    current_order = user.current_order!!
                    isWorking.postValue(true)
                }
                if (user.make_favours){
                    make_favours.postValue(true)
                }
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                initUser(token,id)
            }
        }
    }


    fun updateFcmToken(token:String):LiveData<Boolean>{
        val liveBool = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            while (liveBool.value!! == null){
                try {
                    val res = apiRequest { api.patchFcmToken(token!!,userId!!, UpdateFcmRequest(token)) }
                    liveBool.postValue(true)
                } catch (e: FoodieApiException){
                    if (e.code!=500){
                        liveBool.postValue(false)
                        apiError.postValue(e)
                    }
                }
            }
        }
        return liveBool
    }


    fun getCurrentOffers():LiveData<List<FavourOffer>>{
        val offers = MutableLiveData<List<FavourOffer>>().apply {
            value = null
        }
        Coroutines.io {
            try{
                val apiResponse = apiRequest { api.getCurrentFavourOffers(token!!,userId!!) }
                offers.postValue(apiResponse)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                offers.postValue(ArrayList<FavourOffer>())
            }
        }
        return offers
    }


    fun getFavourOffer(offer_id:Long):LiveData<FavourOffer>{
        observedFavourOffer = MutableLiveData<FavourOffer>().apply {
            value = null
        }

        Coroutines.io {
            try {
                val apiResponse = apiRequest { api.getFavourOffer(token!!,offer_id) }
                observedFavourOffer!!.postValue(apiResponse)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
            }
        }
        return observedFavourOffer!!
    }

    fun acceptOffer(offer_id:Long):LiveData<Boolean>{

        val successResponse = MutableLiveData<Boolean>().apply{
            value = null
        }

        Coroutines.io {
            try{
                val apiResponse = apiRequest {api.changeFavourOfferState(token!!,userId!!,offer_id, StateChangeRequest("accepted")) }
                successResponse.postValue(true)
                currentUser.value!!.state = "working"
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                successResponse.postValue(false)
            }
        }

        return successResponse
    }

    fun rejectOffer(offer_id:Long):LiveData<Boolean>{
        val successResponse = MutableLiveData<Boolean>().apply{
            value = null
        }
        Coroutines.io{
            try{
                val apiResponse = apiRequest{api.changeFavourOfferState(token!!,userId!!,offer_id,
                    StateChangeRequest("rejected")
                )}
                successResponse.postValue(true)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                successResponse.postValue(false)
            }
        }
        return successResponse
    }

    fun getAllShops():LiveData<List<Shop>>{
        val ans = db.getShopDao().getAllOrdered()
        if (ans.value == null || ans.value!!.isEmpty() || ans.value!!.size < 8){
            Coroutines.io{
                try {
                    val top = api.getShopsPage(token!!,0,1000)
                    if (top.isSuccessful){
                        db.getShopDao().upsertBatch((top.body()!!))
                    }
                } catch (e:FoodieApiException) {
                    throw e
                }
            }
        }

        return ans
    }

    fun setTakingFavours(taking:Boolean):LiveData<Boolean>{
        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io{
            try {
                val apiResponse = apiRequest { api.putTakingFavours(token!!,userId!!,TakeFavoursRequest(taking)) }
                liveData.postValue(true)
                if (taking != make_favours.value!!){
                    make_favours.postValue(taking)
                }
            } catch (e:FoodieApiException){
                liveData.postValue(false)
            }
        }
        return liveData
    }

    fun getMoreShops(){
        Coroutines.io{
            try{
                val nextPage = db.getShopDao().getCount()  / SHOP_PAGE_SIZE
                val moreShops = apiRequest{api.getShopsPage(token!!,nextPage,SHOP_PAGE_SIZE)}
                db.getShopDao().upsertBatch(moreShops)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
            }

        }
    }

    fun newOrder(shopId:Long){
        currentOrder = OrderModel(currentUser.value!!.user_id,shopId)
    }

    override fun getMenu(id:Long): LiveData<List<MenuItem>>{
        val liveMenu = db.getMenuItemDao().loadMenu(id)
        if (liveMenu.value.isNullOrEmpty()){
            Coroutines.io {
                try {
                    val products = apiRequest{ api.getMenu(token!!,id) }
                    db.getMenuItemDao().upsert(products)
                } catch(e:FoodieApiException){
                    apiError.postValue(e)
                }
            }
        }
        return liveMenu
    }

    fun addItemToOrder(item: OrderItem,name:String,itemPrice:Float) {
        currentOrder!!.addItem(item,name,itemPrice)
    }

    fun askDeliveryPrice(lat:Double,long:Double,shop_id:Long,delivery_id:Long): LiveData<PricingResponse>{

        val liveData = MutableLiveData<PricingResponse>().apply {
            value = null
        }

        Coroutines.io {
            try {
                val priceResponse = apiRequest { api.getDeliveryPrice(token!!,shop_id,lat,long,userId!!,delivery_id) }
                liveData.postValue(priceResponse)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                liveData.postValue(PricingResponse(-1.0,-1.0))
            }
        }
        return liveData
    }

    fun setOrderCoordinates(lat:Double,lon:Double){
        currentOrder!!.latitude = lat
        currentOrder!!.longitude = lon
    }


    fun confirmOrder(discount:Boolean,favour:Boolean=false):LiveData<Boolean> {

        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val orderId = apiRequest{ api.confirmOrder(token!!,
                    OrderRequest(currentOrder!!.shopId
                        ,currentOrder!!.items.values
                        ,Coordinates(currentOrder!!.latitude!!,currentOrder!!.longitude!!)
                        ,favour
                        ,0
                        ,currentOrder!!.price.toFloat()
                        ,userId!!,discount)) }.order_id
                currentOrder!!.id = orderId
                liveData.postValue(true)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                liveData.postValue(false)
            }

        }

        return liveData
    }

    override fun getShop(id:Long):LiveData<Shop>{
        val shop = db.getShopDao().loadShop(id)
        shop.value?: Coroutines.io {
            try{
                val fetched = apiRequest { api.getShop(token!!,id) }
                db.getShopDao().upsert(fetched)
            } catch (e: FoodieApiException){
                apiError.postValue(e)
            }

        }
        return shop
    }

    fun getCurrentShop():LiveData<Shop>{
        return getShop(currentOrder!!.shopId)
    }

    fun refreshDeliveries(lat:Double,long:Double,favour: Boolean = false){
        Coroutines.io{
           try {
               val lat_rounded = Math.round(lat* 1000.0) / 1000.0
               val lon_roundded = Math.round(long * 1000.0) / 1000.0
               if (!favour){
                   val response = apiRequest{ api.getDeliveries(token!!,lat_rounded,lon_roundded) }
                   availableDeliveries.postValue(response)
               } else {
                   val response = apiRequest { api.getFavourUsers(token!!,lat_rounded,lon_roundded) }
                   response.removeIf {
                       it.user_id == userId
                   }
                   availableDeliveries.postValue(response)
               }
           } catch (e: FoodieApiException){
               if (e.code != 500)
                   apiError.postValue(e)
               availableDeliveries.postValue(ArrayList<User>())
           }
        }
    }

    fun postOffer(deliveryId:Long,order_id:Long,price:Double,pay:Double):LiveData<Long>{
        val liveData = MutableLiveData<Long>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val apiResponse = apiRequest { api.postOffer(token!!,deliveryId,PostOfferRequest(price,pay,order_id,deliveryId)) }
                liveData.postValue(apiResponse.id)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                liveData.postValue(-1)
            }
        }
        return liveData
    }

    override fun getOrder(id:Long): LiveData<Order>{
        val order = db.getOrderDao().getOrder(id)
        if (order.value == null || order.value?.state == "created" || order.value?.state=="onWay")  {
            Coroutines.io {
                try {
                    val res = apiRequest { api.getOrder(token!!, id) }
                    db.getOrderDao().upsert(res)
                } catch (e: FoodieApiException) {
                    apiError.postValue(e)
                } catch (e:Exception){
                    Log.d("Db error",e.message)
                }
            }
        }
        return order
    }

    fun getDelivery(id:Long): LiveData<Delivery> {
        val delivery = db.getDeliveryDao().getDelivery(id)
        if (delivery.value == null){
            Coroutines.io {
                try {
                    val fetched = apiRequest{ api.getDelivery(token!!,id) }
                    db.getDeliveryDao().upsert(fetched)
                } catch (e:FoodieApiException){
                    apiError.postValue(e)
                }
            }
        }
        return delivery
    }

    fun getActiveOrders():LiveData<List<Order>>{
        val live = MutableLiveData<List<Order>>().apply {
            value = null
        }

        Coroutines.io{
            try {
                val onWay = apiRequest { api.getOrdersByState(token!!
                    ,currentUser.value!!.user_id
                    ,"onWay") }
                val pickedUp = apiRequest { api.getOrdersByState(token!!
                    ,currentUser.value!!.user_id
                    ,"pickedUp") }
                val ans = ArrayList<Order>()
                ans.addAll(onWay)
                ans.addAll(pickedUp)
                live.postValue(ans)
            } catch (e:FoodieApiException){
                if (e.code == 500){
                    delay(750)
                    val onWay = apiRequest { api.getOrdersByState(token!!
                        ,currentUser.value!!.user_id
                        ,"onWay") }
                    val pickedUp = apiRequest { api.getOrdersByState(token!!
                        ,currentUser.value!!.user_id
                        ,"onWay") }
                    val ans = ArrayList<Order>()
                    ans.addAll(onWay)
                    ans.addAll(pickedUp)
                    live.postValue(ans)
                }
            }
        }

        return live
    }

    fun getOrdersByState(state: OrderState):LiveData<List<Order>>{
        val str = state.stringVal
        val res = db.getOrderDao().getOrdersByState(str)
        Coroutines.io{
            try {
                val fetched = apiRequest{ api.getOrdersByState(token!!
                    ,currentUser.value!!.user_id!!
                    ,state.stringVal)}
                db.getOrderDao().upsert(fetched)
            } catch (e: FoodieApiException){
                apiError.postValue(e)
            }
        }
        return res
    }

    fun getUserPoints():Int{
        //TODO
        return -1
    }

    override fun updatePosition(latitude:Double,longitude:Double):LiveData<Boolean>{
        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            token?.let{
                try {
                    val apiResponse = apiRequest {
                        api.updateCoordinates(token!!,userId!!,
                            Coordinates(latitude,longitude))
                    }
                    liveData.postValue(true)
                    currentUser.value?.latitude = latitude
                    currentUser.value?.longitude = longitude
                } catch (e:FoodieApiException){
                    apiError.postValue(e)
                    liveData.postValue(false)
                }
            }
        }
        return liveData
    }




    fun getOffer(offer_id:Long):LiveData<Offer>{

        observedOffer = MutableLiveData<Offer>().apply {
            value = null
        }

        Coroutines.io {
            while (observedOffer!!.value == null) {
                try {
                    val apiResponse = apiRequest { api.getOffer(token!!, offer_id) }
                    observedOffer!!.postValue(apiResponse)
                } catch (e: FoodieApiException) {
                    apiError.postValue(e)
                    if (e.code != 500) {
                        break
                    }
                }
            }
        }
        return observedOffer!!
    }


    fun changePassword(new_pass:String):LiveData<SuccessResponse>{
        val live = MutableLiveData<SuccessResponse>().apply {
            value = null
        }
        Coroutines.io{
            try {
                val apiResponse = apiRequest { api.changePassword(token!!,userId!!,
                    ChangePasswordRequest(new_pass)) }
                live.postValue(apiResponse)
            } catch (e:FoodieApiException){
                apiError.postValue(e)
            }
        }
        return live
    }

    fun updateObservedOffer(id:Long,isFavour:Boolean = false){
        Coroutines.io {
            try{
                if (isFavour){
                    delay(5000)
                    val apiResponse = apiRequest{ api.getFavourOffer(token!!,id) }
                    observedFavourOffer?.postValue(apiResponse)
                } else {
                    delay(5000)
                    val apiResponse = apiRequest{ api.getOffer(token!!,id) }
                    observedOffer?.postValue(apiResponse)
                }

            } catch ( e: FoodieApiException){
                apiError.postValue(e)
                delay(500)
                updateObservedOffer(id,isFavour)
            }
        }
    }


    fun updatePic(url:String):LiveData<SuccessResponse>{
        val live = MutableLiveData<SuccessResponse>().apply {
            value = null
        }
        Coroutines.io{
            try{
                val apiResp = apiRequest { api.updateUserPicture(token!!,userId!!,UpdatePictureRequest(url)) }
                live.postValue(apiResp)
            } catch (e: FoodieApiException){
                apiError.postValue(e)
            }
        }
        return live
    }

    override fun getOrderItems(order_id:Long):LiveData<List<com.fpondarts.foodie.data.db.entity.OrderItem>>{
        var liveData = db.getOrderItemDao().getOrderItems(order_id)
        if (liveData.value.isNullOrEmpty()){
            liveData = MutableLiveData<List<com.fpondarts.foodie.data.db.entity.OrderItem>>().apply {
                value = null
            }
            Coroutines.io{
                try {
                    val apiResponse = apiRequest { api.getOrderItems(token!!,order_id) }
                    liveData.postValue(apiResponse)
                    db.getOrderItemDao().upsert(apiResponse)
                } catch (e:FoodieApiException){
                    apiError.postValue(e)
                }
            }
        }
        return liveData
    }

    override fun getMenuItem(product_id:Long):LiveData<MenuItem>{
        val item = db.getMenuItemDao().loadItem(product_id)
        if (item.value == null){
            Coroutines.io{
                try{
                    val apiResponse = apiRequest { api.getProduct(token!!,product_id) }
                    db.getMenuItemDao().upsert(apiResponse)
                } catch (e :FoodieApiException){
                    apiError.postValue(e)
                }
            }
        }
        return item
    }

    fun rateShop(order_id:Long,rating:Float):LiveData<Boolean>{
        val live = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val apiResponse = apiRequest { api.rateShop(token!!,order_id, ReviewRequest(rating)) }
                live.postValue(true)
            } catch (e: FoodieApiException){
                if (e.code == 500){
                    val apiResponse = apiRequest { api.rateDelivery(token!!,order_id, ReviewRequest(rating)) }
                    live.postValue(true)
                } else {
                    live.postValue(false)
                }
            }
        }
        return live
    }

    fun rateDelivery(order_id:Long,rating:Float):LiveData<Boolean>{
        val live = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val apiResponse = apiRequest { api.rateDelivery(token!!,order_id, ReviewRequest(rating)) }
                live.postValue(true)
            } catch (e: FoodieApiException){
                if (e.code == 500){
                    val apiResponse = apiRequest { api.rateDelivery(token!!,order_id, ReviewRequest(rating)) }
                    live.postValue(true)
                } else {
                    live.postValue(false)
                }
            }
        }
        return live
    }

    override fun getUser(user_id:Long):LiveData<User>{
         val liveUser = MutableLiveData<User>().apply {
             value = null
         }
        Coroutines.io {
            try {
                val response = apiRequest { api.getUserById(token!!,user_id) }
                liveUser.postValue(response)
            } catch (e:FoodieApiException) {
                liveUser.postValue(null)
                apiError.postValue(e)
            }
        }
        return liveUser
    }

    override fun getDeliveredByMe():LiveData<List<Order>>{
        val liveData = db.getOrderDao().getAll()

        if (liveData.value.isNullOrEmpty()){
            Coroutines.io {
                try {
                    val response = apiRequest { api.getDeliveredBy(token!!,userId!!) }
                    db.getOrderDao().upsert(response)
                } catch (e: FoodieApiException){
                    if (e.code == 500){
                        delay(500)
                        getDeliveredByMe()
                    }
                }
            }


        }


        return liveData
    }



    fun changeOrderState(order_id:Long, state:String="delivery"):LiveData<Boolean>{
        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io{
            try {
                val apiResponse = apiRequest { api.finishOrder(token!!,order_id,StateChangeRequest(state)) }
                liveData.postValue(true)
                currentUser.value!!.state = "free"
            } catch (e:FoodieApiException){
                apiError.postValue(e)
                liveData.postValue(false)
            }
        }
        return liveData
    }

    fun getRoute(origin: LatLng, destination: LatLng, waypoint: LatLng?): LiveData<Directions>{
        val response = MutableLiveData<Directions>().apply {
            value = null
        }
        Coroutines.io{
            try{

                val originStr = origin.latitude.toString() + "," + origin.longitude.toString()
                val destinationStr = destination.latitude.toString() + "," + destination.longitude.toString()
                var waypointStr = waypoint?.latitude.toString() + "," + waypoint?.longitude.toString()

                if (waypoint == null)
                    waypointStr = ""

                val directions = apiRequest{ directionsApi.getRoute(originStr,destinationStr,waypointStr) }

                response.postValue(directions)


            } catch (e:FoodieApiException){
                apiError.postValue(e)
            }


        }

        return response
    }

    fun postFavourOffer(del_id:Long,order_id:Long,points:Int):LiveData<Long>{
        val liveData = MutableLiveData<Long>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val response = apiRequest { api.postFavourOffer(token!!,del_id,PostFavourOfferRequest(del_id,order_id,points)) }
                liveData.postValue(response.id)
            } catch(e: FoodieApiException){
                apiError.postValue(e)
                liveData.postValue(-1)
            }
        }
        return liveData
    }

    fun notifyOrderDelivered(userFbId:String,order_id:Long):LiveData<Boolean>{
        val live = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io{
            try {
                val jsonObject = JsonObject()
                jsonObject.addProperty("to","/topics/$userFbId")
                val data = JsonObject()
                data.addProperty("title","Orden $order_id")
                data.addProperty("message","La orden ha sido entregada")
                jsonObject.add("data",data)
                val res = apiRequest { fcmApi.pushNotification(jsonObject) }
                live.postValue(true)
            } catch (e:Exception){
                live.postValue(false)
            }
        }
        return live
    }


    fun notifyOrderPickedUp(userFbId:String,order_id:Long):LiveData<Boolean>{
        val live = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io{
            try {
                val jsonObject = JsonObject()
                jsonObject.addProperty("to","/topics/$userFbId")
                val data = JsonObject()
                data.addProperty("title","Orden $order_id")
                data.addProperty("message","La orden ha sido recogida de la tienda")
                jsonObject.add("data",data)

                val res = apiRequest { fcmApi.pushNotification(jsonObject) }

                live.postValue(true)
            } catch (e:Exception){
                live.postValue(false)
            }
        }
        return live
    }

    fun upgradeSuscription(cardNumber:String,cvv:String):LiveData<Boolean>{
        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val res = apiRequest { api.upgradeSuscription(token!!,userId!!,
                    SuscriptionRequest(cardNumber,cvv)
                ) }
                liveData.postValue(true)
            } catch (e:FoodieApiException){
                liveData.postValue(false)
            }
        }
        return liveData
    }

    fun cancelSuscription():LiveData<Boolean>{
        val liveData = MutableLiveData<Boolean>().apply {
            value = null
        }
        Coroutines.io {
            try {
                val res = apiRequest { api.cancelSuscription(token!!,userId!!
                ) }
                liveData.postValue(true)
            } catch (e:FoodieApiException){
                liveData.postValue(false)
            }
        }
        return liveData
    }

}