package com.patrick.test.jsonpoller

// Transfer Objects
import spray.json.DefaultJsonProtocol

case class UrlItemList(urls: List[UrlItem])
case class UrlItem(url: String)
case class ResultItem(message: String, delay: Int, path: String)

object MyJsonSupport extends DefaultJsonProtocol {
  implicit val urlItemFormat = jsonFormat1(UrlItem)
  implicit val urlItemListFormat = jsonFormat1(UrlItemList)
  implicit val resultItemFormat = jsonFormat3(ResultItem)
}
