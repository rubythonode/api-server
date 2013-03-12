package controllers

import play.api._
import play.api.mvc._
import play.api.data.Forms._
import play.api.data._

import play.api.Play.current
import play.api.libs._
import play.api.cache._
import play.api.libs.json._
import play.api.libs.functional.syntax._

import models._
import anorm._ 

object Graph extends Controller with Secured {

  /*
  {
    "point": {
      "type": "user",
      "identifier": "eces@mstock.org",
      "data": {
        "plan": "startup"
      }
    }
  }
   */
  def addPoint(accId: Long) = SignedAPI(accId) { implicit request =>
    try {
      var content = request.body.asJson
      request.queryString.get("json").flatMap(_.headOption) match {
        case Some(json) => {
          content = Some(Json.parse(json))
        }
        case None => 
      }
      content.map { json =>
        (json \ "point").asOpt[JsObject].map { obj =>
          var code: Int = 0
          var msg: String = ""

          var Word = """(\w+)""".r
          var typeString = ""
          (obj \ "type").asOpt[String] match {
            case Some(Word(ts)) => typeString = ts
            case None => throw new Exception("""Json object 'type' is required like this: { "point": {"type": ... } } """)
            case _ => throw new Exception("""Json object 'type' is illegal. point.type should be combination of word and number. """)
          } 

          var identifier = (obj \ "identifier").asOpt[String]
          var _identifier: String = identifier match {
            case Some(value: String) => value
            case None => ""
          }

          var data:Option[JsObject] = (obj \ "data").asOpt[JsObject]
          var _data:JsObject = data match {
            case Some(obj: JsObject) => obj
            case None => {
              msg += "json.invalid"
              Json.obj()
            }
          }

          var point: Point = Point(accId, typeString, _identifier, _data)
          point.id match {
            case NotAssigned => {
              Point.add(point) map { id: Long =>
                point.id = new Id(id)
                code = 201
              } getOrElse {
                InternalServerError(Json.obj(
                  "status" -> Json.obj(
                    "code" -> 500,
                    "message" -> "Point not created. try again"
                  )
                ))
              }
            }
            case id: Pk[Long] => {
              code = 200
              msg += "Already defined. "
            }
          }

          val result: JsObject = Json.obj(
            "status" -> Json.obj(
              "code" -> code,
              "message" -> msg
            ),
            "point" -> Json.obj(
              "id" -> point.id.get,
              "type" -> typeString,
              "identifier" -> _identifier,
              "data" -> _data,
              "_url" -> routes.Graph.getPoint(accId, point.id.get).absoluteURL()
            )
          )  
          
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => {
              if(code == 200){
                Ok(Jsonp(callback, result))
              }else{
                // 201
                Created(Jsonp(callback, result))
              }
            }
            case None => {
              if(code == 200){
                Ok(result)
              }else{
                // 201
                Created(result)
              }
            }
          }
          
        } getOrElse {
          throw new Exception("""Json object 'point' is required like this: { "point": ... } """)
        }
      } getOrElse {
        throw new Exception("""Json object 'point' is required like this: { "point": ... } """)
      }
    } catch { 
      case e: Exception =>
        val json = Json.obj(
          "status" -> Json.obj(
            "code" -> 400,
            "message" -> {
              e.getMessage()
            }
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => BadRequest(json)
        }
    }
  }

  def getPoint(accId: Long, id: Long) = SignedAPI(accId) { implicit request =>
    Point.findOneById(accId, id) map { point: Point =>
      PointType.findOneById(point.typeId) map { pointType: PointType =>
        var _id: Long = point.id.get
      
        val json = Json.obj(
          "status" -> Json.obj(
            "code" -> 200,
            "message" -> ""
          ),
          "point" -> Json.obj(
            "id" -> _id,
            "type" -> pointType.name,
            "identifier" -> point.identifier,
            "createdAt" -> point.createdAt,
            "updatedAt" -> point.updatedAt,
            "referencedAt" -> point.referencedAt,
            "data" -> point.data,
            "_url" -> routes.Graph.getPoint(accId, _id).absoluteURL()
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => Ok(json)
        }
      } getOrElse {
        var json = Json.obj(
          "status" -> Json.obj(
            "code" -> 400,
            "message" -> "point(identifier=%1$s) is invalid.".format(point.identifier)
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => Ok(json)
        }
      }
    } getOrElse {
      request.queryString.get("callback").flatMap(_.headOption) match {
        case Some(callback) => Ok(Jsonp(callback, Application.JsonStatus(404)))
        case None => Application.NotFoundJson()
      }
    }
  }

  def getPointTypes(accId: Long) = SignedAPI(accId) { implicit request =>
    val listOfPointTypes: Seq[JsString] = PointType.findAllByAccountId(accId) map(JsString)
    val jsPointTypes = new JsArray(listOfPointTypes)
    Ok(jsPointTypes)
  }

  def getPointLatest(accId: Long) = SignedAPI(accId) { implicit request =>
    val list: List[Point] = Point.findAllByLatest(accId)
    if(list.length == 0){
      Application.NotFoundJson(404, "Point not found")  
    }else{
      try {
        var array: JsArray = new JsArray()
        list.foreach { point: Point =>
          PointType.findOneById(point.typeId) map { pt =>
            var _id: Long = point.id.get
            array = Json.obj(
              "id" -> _id,
              "type" -> pt.name,
              "identifier" -> point.identifier,
              "createdAt" -> point.createdAt,
              "updatedAt" -> point.updatedAt,
              "referencedAt" -> point.referencedAt,
              "data" -> point.data,
              "_url" -> routes.Graph.getPoint(accId, _id).absoluteURL()
            ) +: array
          } getOrElse {
            throw new Exception("point type of '%1$s' cannot be found. point(identifier=%1$s) is invalid.".format(point.identifier)) 
          }
        }
        var result: JsObject = Json.obj(
          "status" -> Json.obj(
              "code" -> 200,
              "message" -> ""
            ),
          "points" -> array
          )
        Ok(result)
      } catch {
        case e: Exception => {
          val json = Json.obj(
            "status" -> Json.obj(
              "code" -> 400,
              "message" -> {
                e.getMessage()
              }
            )
          )
          BadRequest(json)
        }
      }
    }
  }
  def getPointByTypeOrIdentifier(accId: Long) = SignedAPI(accId) { implicit request =>
    var typeString: String = request.queryString.get("type").flatMap(_.headOption).getOrElse("").toString
    var identifier: String = request.queryString.get("identifier").flatMap(_.headOption).getOrElse("").toString
    val Number = "([0-9]+)".r
    var limit: Int = 10
    var offset: Int = 0
    request.queryString.get("limit").flatMap(_.headOption).getOrElse(10) match {
      case Number(_s) => {
        limit = _s.toInt
      }
      case _ => 
    }
    request.queryString.get("offset").flatMap(_.headOption).getOrElse(0) match {
      case Number(_s) => {
        offset = _s.toInt
      }
      case _ => 
    }
    try {
      if(typeString != "" && identifier != ""){
        var pointType: PointType = null
        PointType.findOneByName(typeString) map { pt =>
          pointType = pt
        } getOrElse {
          throw new Exception("point(identifier=%1$s, type=%2$s) type: '%2$s' isn't supported.".format(identifier, typeString))
        }

        Point.findOneByTypeIdAndIdentifier(accId, pointType.id.get, identifier) map { point: Point =>
          var _id: Long = point.id.get
          val json = Json.obj(
            "status" -> Json.obj(
              "code" -> 200,
              "message" -> ""
            ),
            "point" -> Json.obj(
              "id" -> _id,
              "type" -> pointType.name,
              "identifier" -> point.identifier,
              "createdAt" -> point.createdAt,
              "updatedAt" -> point.updatedAt,
              "referencedAt" -> point.referencedAt,
              "data" -> point.data,
              "_url" -> routes.Graph.getPoint(accId, _id).absoluteURL()
            ),
            "_length" -> 1
          )
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, json))
            case None => Ok(json)
          }  
        } getOrElse {
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, Application.JsonStatus(404, "Point not found")))
            case None => Application.NotFoundJson(404, "Point not found")
          }
        }
      }else if(typeString != ""){
        var typeId: Long = -1
        PointType.findOneByName(typeString) map { pt =>
          typeId = pt.id.get
        } getOrElse {
          throw new Exception("point(identifier=?, type=%1$s) type: '%1$s' isn't supported.".format(typeString))
        }
        val list: List[Point] = Point.findAllByTypeId(accId, typeId, limit, offset)
        if(list.length == 0){
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, Application.JsonStatus(404, "Point not found")))
            case None => Application.NotFoundJson(404, "Point not found")
          }
        }else{
          var array: JsArray = new JsArray()

          list.foreach { point: Point =>
            var _id: Long = point.id.get
            var ptn: String = null
            PointType.findOneById(point.typeId).map { pt =>
              ptn = pt.name
            }.getOrElse {
              throw new Exception("point(typeId=%1$d) isn't supported.".format(point.typeId))
            }
            array = Json.obj(
              "id" -> _id,
              "type" -> ptn,
              "identifier" -> point.identifier,
              "createdAt" -> point.createdAt,
              "updatedAt" -> point.updatedAt,
              "referencedAt" -> point.referencedAt,
              "data" -> point.data,
              "_url" -> routes.Graph.getPoint(accId, _id).absoluteURL()
            ) +: array
          }
          
          val current: String = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?type=%s&limit=%d&offset=%d".format(typeString, limit, offset)
          val next = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?type=%s&limit=%d&offset=%d".format(typeString, limit, offset+limit)
          val prev = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?type=%s&limit=%d&offset=%d".format(typeString, limit, math.max(0,offset-limit) )
          // var next: String = ""
          // var prev: String = ""
          // if(list.length > offset + limit){
          //   next = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?type=%s&limit=%d&offset%d".format(typeString, limit, offset+limit)
          // }else{
          //   next = current + '#'
          // }
          // val pages = offset / limit
          // if(pages > 0){
          //   prev = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?type=%s&limit=%d&offset%d".format(typeString, limit, math.min(0,offset-limit) )
          // }else{
          //   prev = current + '#'
          // }
          var result: JsObject = Json.obj(
            "status" -> Json.obj(
                "code" -> 200,
                "message" -> ""
              ),
            "points" -> array,
            "_length" -> list.length, 
            "_previous" -> prev,
            "_current" -> current,
            "_next" -> next
            )
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, result))
            case None => Ok(result)
          }
        }
      }else if(identifier != ""){
        val list: List[Point] = Point.findAllByIdentifier(accId, identifier, limit, offset)
        if(list.length == 0){
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, Application.JsonStatus(404, "Point not found")))
            case None => Application.NotFoundJson(404, "Point not found")
          }
        }else{
          var array: JsArray = new JsArray()
          list.foreach { point: Point =>
            var _id: Long = point.id.get
            var ptn: String = null

            PointType.findOneById(point.typeId).map { pt =>
              ptn = pt.name
            }.getOrElse {
              throw new Exception("point(typeId=%1$d) isn't supported.".format(point.typeId))
            }

            array = Json.obj(
              "id" -> _id,
              "type" -> ptn,
              "identifier" -> point.identifier,
              "createdAt" -> point.createdAt,
              "updatedAt" -> point.updatedAt,
              "referencedAt" -> point.referencedAt,
              "data" -> point.data,
              "_url" -> routes.Graph.getPoint(accId, _id).absoluteURL()
            ) +: array
          }
          
          val current: String = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?identifier=%s&limit=%d&offset%d".format(identifier, limit, offset)
          var next: String = ""
          var prev: String = ""
          if(list.length > offset + limit){
            next = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?identifier=%s&limit=%d&offset%d".format(identifier, limit, offset+limit)
          }else{
            next = current + '#'
          }
          val pages = offset / limit
          if(pages > 0){
            prev = routes.Graph.getPointByTypeOrIdentifier(accId).absoluteURL() + "?identifier=%s&limit=%d&offset%d".format(identifier, limit, math.min(0,offset-limit) )
          }else{
            prev = current + '#'
          }
          var result: JsObject = Json.obj(
            "status" -> Json.obj(
                "code" -> 200,
                "message" -> ""
              ),
            "points" -> array,
            "_length" -> list.length,
            "_previous" -> prev,
            "_current" -> current,
            "_next" -> next
            )
          request.queryString.get("callback").flatMap(_.headOption) match {
            case Some(callback) => Ok(Jsonp(callback, result))
            case None => Ok(result)
          }
        }
      }else{
        throw new Exception("point(identifier=?, type=?) how can I do for you? ") 
      }
    } catch { 
      case e: Exception =>
        val json = Json.obj(
          "status" -> Json.obj(
            "code" -> 400,
            "message" -> {
              e.getMessage()
            }
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => BadRequest(json)
        }
    }
  }

  /*
  {
    "edge": {
      "subjectId": 1,
      "verb": "read",
      "objectId": 2
    }
  }
   */
  def linkWithEdge(accId: Long) = SignedAPI(accId) { implicit request =>
    try {
      var content = request.body.asJson
      request.queryString.get("json").flatMap(_.headOption) match {
        case Some(json) => {
          content = Some(Json.parse(json))
        }
        case None => 
      }
      content.map { json =>
        (json \ "edge").asOpt[JsObject].map { e =>
          val edgeRead = (
            (__ \ "verb").read[String] ~
            (__ \ "subjectId").read[String] ~
            (__ \ "objectId").read[String] ~
            (__ \ "subjectType").read[String] ~
            (__ \ "objectType").read[String]
          ) tupled

          edgeRead.reads(e).fold(
            valid = { edgeContent =>              
              var _sId = edgeContent._2
              var _oId = edgeContent._3

              var v = edgeContent._1
              var sId = 0L
              var oId = 0L
              var sTypeId = 0L
              var oTypeId = 0L

              if(v.length < 3){
                throw new Exception("point(type=?, id=?) '?' point(type=?, id=?): verb must have at least 3 characters.")
              }
              if(v.length > 20){
                throw new Exception("point(type=?, id=?) '?' point(type=?, id=?): verb must have less than or 20 characters.")
              }
              
              Point.findOneByTypeNameAndIdentifier(accId, edgeContent._4, edgeContent._2).map { subjectPoint =>
                sId = subjectPoint.id.get
                sTypeId = subjectPoint.typeId
              }.getOrElse {
                throw new Exception("unknown point(type=%1$s, identifier=%2$s) '%3$s' point(type=%4$s, identifier=%5$s): subject point isn't found.".format(edgeContent._4, edgeContent._2, v, edgeContent._5, edgeContent._3))
              }

              Point.findOneByTypeNameAndIdentifier(accId, edgeContent._5, edgeContent._3).map { objectPoint =>
                oId = objectPoint.id.get
                oTypeId = objectPoint.typeId
              }.getOrElse {
                throw new Exception("point(type=%1$s, identifier=%2$s) '%3$s' unknown point(type=%4$s, identifier=%5$s): object point isn't found.".format(edgeContent._4, edgeContent._2, v, edgeContent._5, edgeContent._3))
              }

              if(sTypeId == oTypeId){
                throw new Exception("point(type=%1$s, identifier=%2$s) '%3$s' point(type=%4$s, identifier=%5$s): no self-reference and iteratable relationship are allowed.".format(edgeContent._4, edgeContent._2, v, edgeContent._5, edgeContent._3))
              }
              val edge = Edge(accId, sId, sTypeId, v, oId, oTypeId)
              Edge.add( accId, edge ) map { id: Long =>
                val json = Json.obj(
                  "status" -> Json.obj(
                    "code" -> 201,
                    "message" -> "Edge created."
                  )
                )
                request.queryString.get("callback").flatMap(_.headOption) match {
                  case Some(callback) => Created(Jsonp(callback, json))
                  case None => Created(json)
                }
              } getOrElse {
                val json = Json.obj(
                  "status" -> Json.obj(
                    "code" -> 500,
                    "message" -> {
                      "Edge not created. Try again."
                    }
                  )
                )
                request.queryString.get("callback").flatMap(_.headOption) match {
                  case Some(callback) => Ok(Jsonp(callback, json))
                  case None => InternalServerError(json)
                }
              }
            },
            invalid = { error =>
              throw new Exception("%1$s is undefined".format(error.head._1.toJsonString.replace("obj", "edge")))
            }
          )
        } getOrElse {
          throw new Exception("point(type=?, id=?) '...' point(type=?, id=?): no points are selected.")
        }
      } getOrElse {
        throw new Exception("point(type=?, id=?) '...' point(type=?, id=?): no points are selected.")
      }
    } catch { 
      case e: Exception =>
        val json = Json.obj(
          "status" -> Json.obj(
            "code" -> 400,
            "message" -> {
              e.getMessage()
            }
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => BadRequest(json)
        }
    }
  }

  /*
    /v1/account/1/edge?subjectId=1&subjectType=user&verb=read&objectId=0&objectType=post
   */
  def findEdges(accId: Long) = SignedAPI(accId) { implicit request =>
    try {
      val (sI, sT, sS, vOpt, oI, oT, oS) = Form(
        tuple(
          "subjectId" -> optional(longNumber),
          "subjectType" -> optional(text),
          "subjectIdentifier" -> optional(text),
          "verb" -> optional(text),
          "objectId" -> optional(longNumber),
          "objectType" -> optional(text),
          "objectIdentifier" -> optional(text)
        )
      ).bindFromRequest.get

      (sI, sT, sS, vOpt, oI, oT, oS) match {
        case (None, None, None, None, None, None, None) => throw new Exception("edge(subjectId | subjectType | subjectIdentifier | verb | objectId | objectType | objectIdentifier): no description for edge is given.")
        case _ => 
      }

      var sId: Long = sI.getOrElse(-1L)
      var sType = sT.getOrElse("")
      var sTypeId = -1L
      var sIdentifier = sS.getOrElse("")
      var v = vOpt.getOrElse("")
      var oId = oI.getOrElse(-1L)
      var oType = oT.getOrElse("")
      var oTypeId = -1L
      var oIdentifier = oS.getOrElse("")

      var complexity = 0.0
      var audit: List[(String, Long)] = List()
      // check complexity
      if(v == ""){
        complexity += 3
        audit = ("no verb = 3", 3L) +: audit
      }
      var sDefined = true
      var oDefined = true

      var param: List[(String, Any)] = List()

      if(v.length == 1 || v.length == 2){
        param = param :+ ("verb", v)
        throw new Exception("edge(?): verb must have at least 3 characters.")
      }
      if(v.length > 20){
        param = param :+ ("verb", v)
        throw new Exception("edge(?): verb must have less than or 20 characters.")
      }
      
      if(sId != -1){
        param = param :+ ("subjectId", sId)
        Point.getTypeId(accId, sId) match {
          case Some(id: Long) => {
            sTypeId = id
          }
          case _ => {
            // sId = -1
            throw new Exception("unknown point(id=%1$s): subject point isn't found.".format(sId))
          }
        }
      }

      if(oId != -1){
        param = param :+ ("objectId", oId)
        Point.getTypeId(accId, oId) match {
          case Some(id: Long) => {
            oTypeId = id
          }
          case _ => {
            // oId = -1
            throw new Exception("unknown point(id=%1$s): object point isn't found.".format(oId))
          }
        }
      }

      if(sType.length > 0){
        PointType.findOneByName(sType).map { pt =>
          sTypeId = pt.id.get
          param = param :+ ("subjectType", pt.name)
          if(sIdentifier.length > 0){
            Point.findOneByTypeIdAndIdentifier(accId, sTypeId, sIdentifier).map { point =>
              sId = point.id.get
              param = param :+ ("subjectIdentifier", point.identifier)
            }.getOrElse {
              // sIdentifier = ""
              throw new Exception("edge(?): subject identifier of '%1$s' cannot be found.".format(sIdentifier))
            }
          }
        }.getOrElse {
          // sType = ""
          throw new Exception("edge(?): subject type of '%1$s' isn't supported.".format(sType))
        }
      }

      if(oType.length > 0){
        PointType.findOneByName(oType).map { pt =>
          oTypeId = pt.id.get
          param = param :+ ("objectType", pt.name)
          if(oIdentifier.length > 0){
            Point.findOneByTypeIdAndIdentifier(accId, oTypeId, oIdentifier).map { point =>
              oId = point.id.get
              param = param :+ ("objectIdentifier", point.identifier)
            }.getOrElse {
              // oIdentifier = ""
              throw new Exception("edge(?): object identifier of '%1$s' cannot be found.".format(oIdentifier))
            }
          }
        }.getOrElse {
          // oType = ""
          throw new Exception("edge(?): object type of '%1$s' isn't supported.".format(oType))
        }
      }

      if(sId == -1){
        if(sType.length == 0){
          if(sIdentifier.length == 0){
            complexity += 2 
            sDefined = false
            audit = ("no subject id & identifier & type = 2", 2L) +: audit
          }else{
            complexity += 0.7 
            audit = ("no subject id & type but identifier = 0.7", 2L) +: audit
          }
        }else{
          if(sIdentifier.length == 0){
            complexity += 1
            audit = ("no subject id & identifier but type is = 1", 1L) +: audit
          }else{
            // sDefined = true
            // type + identifier = id
          }
        }
      }
      if(oId == -1){
        if(oType.length == 0){
          if(oIdentifier.length == 0){
            complexity += 2 
            oDefined = false
            audit = ("no object id & identifier & type = 2", 2L) +: audit
          }else{
            complexity += 0.7
            audit = ("no object id & type but identifier = 0.7", 2L) +: audit
          }
        }else{
          if(oIdentifier.length == 0){
            complexity += 1
            audit = ("no object id but type is = 1", 1L) +: audit
          }else{
            // oDefined = true
            // type + identifier = id
          }
        }
      }

      //audit = (sDefined + " || " + oDefined, 0L) +: audit
      if((sDefined || oDefined) == false){
        complexity += 2
        audit = ("no models = 2", 2L) +: audit
      }

      val sum = audit.foldLeft(("Audit logs: \n", 0L)){ (a: (String, Long), b: (String, Long)) =>
        (a._1 + "\t" + b._1 + "\n", a._2 + b._2)
      }
      println("Comp = " + sum._2 + "\n" + sum._1)

      val maxComplexity = 9
      /*
       * sId + v + oId = 0
       * sType + v + oType = 2
       * sId + v = 2
       * sId + oId = 3
       * sId = 5
       * sType + oType = 5
       * v = 6
       */
      if(complexity >= 5){
        throw new Exception("edge(?): the pseudo edge specified in query has too many unknown fields. Calculated complexity is %f".format(complexity / maxComplexity))
      }

      // prepare variables and arguments
      
      // find cache

      // generate new query for search
      var args: List[(String, Long)] = List()

      println("QUERY: %s / %s".format(sId, sTypeId))
      if(sId != -1){
        args = args :+ ("sId", sId) :+ ("sType", sTypeId)
        param = param :+ ("subjectId", sId) :+ ("subjectType", sTypeId)
      }else if(sTypeId != -1){
        args = args :+ ("sType", sTypeId)
        param = param :+ ("subjectType", sTypeId)
      }
      if(v.length > 0){
        param = param :+ ("verb", v)
      }
      if(oId != -1){
        args = args :+ ("oId", oId) :+ ("oType", oTypeId)
        param = param :+ ("objectId", oId) :+ ("objectType", oTypeId)
      }else if(oTypeId != -1){
        args = args :+ ("oType", oTypeId)
        param = param :+ ("objectType", oTypeId)
      }
      if(oIdentifier.length > 0){
        param = param :+ ("objectIdentifier", oIdentifier) 
      }

      var optionVerb: Option[String] = None
      if(v.length > 0){
        optionVerb = Some(v)
      }
      val list: List[Edge] = Edge.find(accId, optionVerb, args:_*)

      if(list.length == 0){
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, Application.JsonStatus(404, "Edge not found")))
          case None => Application.NotFoundJson(404, "Edge not found")
        }
      }else{
        val url: String = (param.foldLeft(("", 0)){ (a: (String, Any), b: (String, Any)) => 
          var delim = "&"
          if(a._1.length == 0){
            delim = "?"
          }
          ("%s%s%s=%s".format(a._1, delim, b._1, b._2), 0)
        })._1

        var array: JsArray = new JsArray()
        var subjectType: String = null
        var objectType: String = null

        list.foreach { edge: Edge =>
          /*
            // Other strategy for find a PointType

            val pointTypes: List[PointType] = PointType.findAll // or Cached List[PointType]
            if(pointTypes.filter(_.id.get==edge.sType).length != 0) {
              subjectType = pt.name
            } else {
              throw new Exception
            }

          */ 
          PointType.findOneById(edge.sType).map { pt =>
            subjectType = pt.name
          }.getOrElse {
            throw new Exception("edge(sId=%1$s): subject type of '%2$s' isn't supported.".format(edge.sId, edge.sType))
          }

          PointType.findOneById(edge.oType).map { pt =>
            objectType = pt.name
          }.getOrElse {
            throw new Exception("edge(oId=%1$s): subject type of '%2$s' isn't supported.".format(edge.oId, edge.oType))
          }
          array = Json.obj(
            "subjectId" -> edge.sId,
            "subjectType" -> subjectType,
            "verb" -> edge.v,
            "objectId" -> edge.oId,
            "objectType" -> objectType,
            "createdAt" -> edge.createdAt,
            "_url" -> (routes.Graph.findEdges(accId).absoluteURL() + url)
            ) +: array
        }
        var result: JsObject = Json.obj(
          "status" -> Json.obj(
              "code" -> 200,
              "message" -> ""
            ),
          "edges" -> array,
          "_length" -> list.length
          )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, result))
          case None => Ok(result)
        }
      }
    } catch { 
      case e: Exception =>
        e.printStackTrace()
        println(">> " + request)
        val json = Json.obj(
          "status" -> Json.obj(
            "code" -> 400,
            "message" -> e.getMessage()
          )
        )
        request.queryString.get("callback").flatMap(_.headOption) match {
          case Some(callback) => Ok(Jsonp(callback, json))
          case None => BadRequest(json)
        }
    }
  }
}