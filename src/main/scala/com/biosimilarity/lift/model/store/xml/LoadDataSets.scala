// -*- mode: Scala;-*- 
// Filename:    LoadDataSets.scala 
// Authors:     lgm                                                    
// Creation:    Wed Mar 30 13:54:42 2011 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.lift.model.store.xml.datasets

import com.biosimilarity.lift.model.store.xml._
import com.biosimilarity.lift.model.store.CnxnLabel
import com.biosimilarity.lift.model.store.OntologicalStatus
import com.biosimilarity.lift.model.store.Factual
import com.biosimilarity.lift.model.store.Hypothetical
import com.biosimilarity.lift.model.store.Theoretical
import com.biosimilarity.lift.model.store.CnxnLeaf
import com.biosimilarity.lift.model.store.CCnxnLeaf
import com.biosimilarity.lift.model.store.AbstractCnxnBranch
import com.biosimilarity.lift.model.store.CnxnBranch
import com.biosimilarity.lift.model.store.CCnxnBranch
import com.biosimilarity.lift.model.store.CnxnCtxtLabel
import com.biosimilarity.lift.model.store.CnxnCtxtLeaf
import com.biosimilarity.lift.model.store.CCnxnCtxtLeaf
import com.biosimilarity.lift.model.store.AbstractCnxnCtxtBranch
import com.biosimilarity.lift.model.store.CnxnCtxtBranch
import com.biosimilarity.lift.model.store.CCnxnCtxtBranch
import com.biosimilarity.lift.model.store.CnxnCtxtInjector
import com.biosimilarity.lift.model.store.Cnxn
import com.biosimilarity.lift.model.store.CCnxn
import com.biosimilarity.lift.model.store.CnxnXML
import com.biosimilarity.lift.model.store.CnxnXQuery
import com.biosimilarity.lift.lib._

import org.basex.api.xmldb.BXCollection
import org.basex.core.BaseXException
import org.basex.core.Context
import org.basex.core.cmd.{ List => BXList, _ }

import org.xmldb.api.base._
import org.xmldb.api.modules._
import org.xmldb.api._

import scala.xml._

import javax.xml.transform.OutputKeys
import java.util.UUID
import java.io.File

object CXQ extends CnxnXQuery[String,String,String]
 with CnxnCtxtInjector[String,String,String]
 with UUIDOps
 with CnxnXML[String,String,String]

object BX extends BaseXXMLStore with UUIDOps {
  val datasetsDir = "src/main/resources/datasets/"
  val dbNames : List[String] =
    List( "GraphOne", "GraphTwo", "GraphThree" )
  val dbSets : List[String] =
    List( "Route", "Connection", "Scale" )  

  val vertexQuery1 = "//VertexString"
  val vertexQuery2 = "//Pointed"
  val edgeQuery1 = "//Connection"
  val edgeQuery2 = "//EdgeName"

  val outerGraphExpr =
    "Connected( EdgeString( WS ), X, Y )"
  val outerGraphExprCCL =
    CXQ.fromCaseClassInstanceString( outerGraphExpr ).getOrElse( null ).asInstanceOf[CnxnCtxtLabel[String,String,String]]
  val outerGraphExprXQuery =
    CXQ.xqQuery( outerGraphExprCCL )
  val firstVandG =
    "VertexSelection( LRBoundVertex( VELabel, V ), G )"
  val firstVandGCCL =
    CXQ.fromCaseClassInstanceString( firstVandG ).getOrElse( null ).asInstanceOf[CnxnCtxtLabel[String,String,String]]
  val firstVandGXQuery =
    CXQ.xqQuery( firstVandGCCL )
  val innerGraphExpr = 
    "VertexSelection( LRB, Connected( EdgeString( WS2 ), X1, Y1 ) )"
  val innerGraphExprCCL =
    CXQ.fromCaseClassInstanceString( innerGraphExpr ).getOrElse( null ).asInstanceOf[CnxnCtxtLabel[String,String,String]]
  val innerGraphExprXQuery =
    CXQ.xqQuery( innerGraphExprCCL )
    
  def populateDB(
    dbNameStr : String,      // name of the database
    contentFileStr : String  // root of the file name of the data
  ) = {    
    // new database
    val bxcoll = new BXCollection( dbNameStr, false )

    // new document
    val document =
      bxcoll.createResource(
	null,
	XMLResource.RESOURCE_TYPE
      ).asInstanceOf[XMLResource]

    // add the data
    document.setContent(
      new java.io.File(
	datasetsDir + contentFileStr + ".xml"
      )
    )

    // store the data
    bxcoll.storeResource( document )

    // close the database
    bxcoll.close

    // open the database for operations
    new BXCollection( dbNameStr, true )
  }

  def loadDataSets = {
    dbNames.zip( dbSets ).map(
      { ( dbNData ) => populateDB( dbNData._1, dbNData._2 ) }
    )
  }

  lazy val dataSets = loadDataSets

  def reportGraphs = {
    for( dataSet <- dataSets ) {
      val xqSrvc =
	dataSet.getService(
	  "XPathQueryService",
	  "1.0"
	).asInstanceOf[XPathQueryService]
      val dbName = dataSet.getName
      val vertexResourceSet = xqSrvc.query( vertexQuery2 )
      println( 
	"// *************************************************************"
      )
      println( 
	"// Report for database: " + dbName 
      )
      println( 
	"// *************************************************************"
      )
      println(
	"Number of vertices in this graph is " + vertexResourceSet.getSize + "\n"
      )
      val vrsIter = vertexResourceSet.getIterator
      while( vrsIter.hasMoreResources ) {
	val vertexElemStr = vrsIter.nextResource.getContent.toString
	val vertexElem = XML.loadString( vertexElemStr )
	println( vertexElem )
      }

      val edgeResourceSet = xqSrvc.query( edgeQuery2 )

      println(
	"\n\nNumber of edges in this graph is " + edgeResourceSet.getSize + "\n"
      )

      val ersIter = edgeResourceSet.getIterator
      while( ersIter.hasMoreResources ) {
	val edgeElemStr = ersIter.nextResource.getContent.toString
	val edgeElem = XML.loadString( edgeElemStr )
	println( edgeElem )
      }

      println( 
	"// *************************************************************\n\n"
      )
    }
  }
}


