// -*- mode: Scala;-*- 
// Filename:    TSpaceDesignPattern.scala 
// Authors:     lgm                                                    
// Creation:    Mon Sep  6 17:57:30 2010 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.lift.model.store

import com.biosimilarity.lift.model.ApplicationDefaults
import com.biosimilarity.lift.lib._

import scala.concurrent.{Channel => Chan, _}
import scala.concurrent.cpsops._

import scala.util.continuations._ 
import scala.collection.MapProxy
import scala.collection.mutable.Map
import scala.collection.mutable.HashMap

trait RetentionPolicy
trait RetainInCache extends RetentionPolicy
trait RetainInStore extends RetentionPolicy
case object DoNotRetain extends RetentionPolicy 
case object Cache extends RetainInCache
case object Store extends RetainInStore
case object CacheAndStore extends RetainInCache with RetainInStore

trait ExcludedMiddleTypes[Place,Pattern,Resource] {
  type RK = Option[Resource] => Unit @suspendable  

  class BlockableContinuation(
    val rk : RK
  ) extends Function1[Option[Resource],Unit @suspendable] {
    override def apply(
      orsrc : Option[Resource]
    ) : Unit @suspendable = {
      rk( orsrc )
    }
    def unapply(
      bc : BlockableContinuation
    ) : Option[(RK)] = {
      Some( ( bc.rk ) )
    }
    def blockForValue() = {
      synchronized {
	wait()
      }
    }
    def unblockOnValue() = {
      synchronized {
	notifyAll()
      }
    }
  }

  type Substitution <: Function1[Resource,Option[Resource]]

  case class IdentitySubstitution( )
       extends Function1[Resource,Option[Resource]] {
	 override def apply( rsrc : Resource ) = Some( rsrc )
       }

  case class PlaceInstance(
    place : Place,
    stuff : Either[Resource,List[RK]],
    subst : Substitution
  )
}

trait ExcludedMiddleScope[Place,Pattern,Resource] {
  type EMTypes <: ExcludedMiddleTypes[Place,Pattern,Resource]
  def protoEMTypes : EMTypes
  val emT : EMTypes = protoEMTypes
}

trait MonadicTupleSpace[Place,Pattern,Resource]
//       extends MapLike[Place,Resource, This]
extends MonadicGenerators
with FJTaskRunners
with ExcludedMiddleTypes[Place,Pattern,Resource]
{
  self : WireTap
      with Journalist
      with ConfiggyReporting 
      with ConfigurationTrampoline =>

  trait PortKey
  case class ReaderKey() extends PortKey
  case class WriterKey() extends PortKey

  @transient
  val TheReaderKey : ReaderKey = ReaderKey()
  @transient
  val TheWriterKey : WriterKey = WriterKey() 

  case class SpaceLock( theRoom : HashMap[PortKey,Int] ) {
    def allowedIn( 
      theRoom : HashMap[PortKey,Int], token : ReaderKey
    ) : Boolean = {
      theRoom.get( TheWriterKey ) match {
	case None => {
	  theRoom += ( TheReaderKey -> ( theRoom.get( TheReaderKey ).getOrElse( 0 ) + 1 ) )
	  true
	}
	case _ => false
      }
    }

    def allowedIn( 
      theRoom : HashMap[PortKey,Int], token : WriterKey
    ) : Boolean = {
      theRoom.get( TheReaderKey ) match {
	case None => {
	  theRoom += ( TheWriterKey -> ( theRoom.get( TheWriterKey ).getOrElse( 0 ) + 1 ) )
	  true
	}
	case _ => false
      }
    }

    def leave( 
      theRoom : HashMap[PortKey,Int], token : WriterKey
    ) : Unit = {
      theRoom.get( TheWriterKey ) match {
	case Some( 1 ) => {
	  theRoom -= TheWriterKey
	}
	case Some( i : Int ) => {
	  theRoom += ( TheWriterKey -> ( i - 1 ) )
	}
	case _ => {
	  throw new Exception( "leaving without entering: " + token )
	}
      }      
    }

    def leave( 
      theRoom : HashMap[PortKey,Int], token : ReaderKey
    ) : Unit = {
      theRoom.get( TheReaderKey ) match {
	case Some( 1 ) => {
	  theRoom -= TheReaderKey
	  notify()
	}
	case Some( i : Int ) => {
	  theRoom += ( TheReaderKey -> ( i - 1 ) )
	}
	case _ => {
	  throw new Exception( "leaving without entering: " + token )
	}
      }      
    }

    def occupy( portKey : PortKey ) = synchronized {
      // We test and set in one operation
      portKey match {
	case rk : ReaderKey => {
	  while ( ! allowedIn( theRoom, rk ) ) wait()
	}
	case wk : WriterKey => {
	  while ( ! allowedIn( theRoom, wk ) ) wait()
	}
	case _ => {
	  throw new Exception( "Unexpected portKey type: " + portKey )
	}
      }      
    }

    def depart( portKey : PortKey ) = synchronized {
      portKey match {
	case rk : ReaderKey => leave( theRoom, rk )
	case wk : WriterKey => leave( theRoom, wk )
	case _ => 
	  throw new Exception( "Unexpected portKey type: " + portKey )
      }
      
    }
  }

  @transient
  val spaceLock : SpaceLock = new SpaceLock( new HashMap[PortKey,Int] )

  def theMeetingPlace : Map[Place,Resource]
  def theChannels : Map[Place,Resource]
  def theWaiters : Map[Place,List[RK]]
  def theSubscriptions : Map[Place,List[RK]]

  def fits( ptn : Pattern, place : Place ) : Boolean
  def fitsK( ptn : Pattern, place : Place ) : Option[Substitution]
  def representative( ptn : Pattern ) : Place  

  //def self = theMeetingPlace

  override def itergen[T]( coll : Iterable[T] ) = 
    Generator {
      gk : ( T => Unit @suspendable ) =>
	val collItr = coll.iterator

	while( collItr.hasNext ) {
	  gk( collItr.next )
	}
    }
  
  def locations(
    map : Either[Map[Place,Resource],Map[Place,List[RK]]],
    ptn : Pattern
  ) : List[PlaceInstance] = {
    def lox[Trgt,ITrgt](
      m : Map[Place,Trgt],
      inj : Trgt => ITrgt
    ) : List[(Place,ITrgt,Substitution)]
    = {
      ( ( Nil : List[(Place,ITrgt,Substitution)] ) /: m )( 
	{ 
	  ( acc, kv ) => {
	    val ( k, v ) = kv
	    fitsK( ptn, k ) match {
	      case Some( s ) => acc ++ List[(Place,ITrgt,Substitution)]( ( k, inj( v ), s ) )
	      case None => acc
	    }
	  }
	}
      )
    }

    val triples =
      map match {
	case Left( m ) => {
	  lox[Resource,Either[Resource,List[RK]]](
	    m, ( r ) => Left[Resource,List[RK]]( r )
	  )
	}
	case Right( m ) => {
	  lox[List[RK],Either[Resource,List[RK]]](
	    m, ( r ) => Right[Resource,List[RK]]( r )
	  )
	}
      }
    triples.map(
      ( t ) => { 
	val ( p, e, s ) = t
	PlaceInstance( p, e, s )
      }
    )
  }  

  def mgetWithSuspension(
    channels : Map[Place,Resource],
    registered : Map[Place,List[RK]],
    consume : RetentionPolicy,
    cursor : RetentionPolicy
  )( ptn : Pattern )
  : Generator[Option[Resource],Unit,Unit] =
    Generator {
      rk : ( Option[Resource] => Unit @suspendable ) =>
	shift[Unit,Unit,Unit] {
	  outerk : ( Unit => Unit ) =>
	    reset[Unit,Unit] {
	      val map = Left[Map[Place,Resource],Map[Place,List[RK]]]( channels )
	      val meets = locations( map, ptn )

	      if ( meets.isEmpty )  {
		val place = representative( ptn )
		//println( "did not find a resource, storing a continuation: " + rk )
		val bk = new BlockableContinuation( rk )
		registered( place ) =
		  (
		    registered.get( place ).getOrElse( Nil ) ++ List( bk )
		  )
		//rk( None )
		//println( "get suspending" )
		bk.blockForValue()
		//outerk()
	      }
	      else {
		for(
		  placeNRrscNSubst <- itergen[PlaceInstance](
		    meets
		  )
		) {
		  val PlaceInstance( place, Left( rsrc ), s ) = placeNRrscNSubst
		  
		  //println( "found a resource: " + rsrc )		  		  
		  consume match {
		    case policy : RetainInCache => {
		      channels -= place
		    }
		    case _ => {
		      tweet( "policy indicates not to remove from cache: " + place )
		    }
		  }

		  rk( s( rsrc ) )
		  //println( "get returning" )
		  outerk()
		  //shift { k : ( Unit => Unit ) => k() }
		}
	      }
	      //println( "get returning" )
	      //outerk()
	    }
	}
    }

  def mget(
    channels : Map[Place,Resource],
    registered : Map[Place,List[RK]],
    consume : RetentionPolicy,
    keep : RetentionPolicy
  )( ptn : Pattern )
  : Generator[Option[Resource],Unit,Unit] =
    Generator {
      rk : ( Option[Resource] => Unit @suspendable ) =>
	shift {
	  outerk : ( Unit => Unit ) =>
	    reset {
	      tweet( "Reader occupying spaceLock on " + this + " for mget on " + ptn + "." )
	      // if ( ! spaceLock.available ) {
// 		rk( None )
// 	      }
// 	      else {
		spaceLock.occupy( TheReaderKey )
		val map = Left[Map[Place,Resource],Map[Place,List[RK]]]( channels )
		val meets = locations( map, ptn )
		
		if ( meets.isEmpty ) {
		  val place = representative( ptn )
		  tweet( "did not find a resource, storing a continuation: " + rk )
		  tweet( "registered continuation storage: " + registered )
		  tweet( "theWaiters: " + theWaiters )
		  tweet( "theSubscriptions: " + theSubscriptions )		  
		  
		  keep match {
		    case policy : RetainInCache => {
		      registered( place ) =
			registered.get( place ).getOrElse( Nil ) ++ List( rk )
		    }
		    case _ => {
		      tweet( "policy indicates not to retain in cache: " + rk )
		    }
		  }
		  
		  tweet( "stored a continuation: " + rk )
		  tweet( "registered continuation storage: " + registered )
		  tweet( "theWaiters: " + theWaiters )
		  tweet( "theSubscriptions: " + theSubscriptions )

		  tweet( "Reader departing spaceLock on " + this + " for mget on " + ptn + "." )
		  spaceLock.depart( TheReaderKey )
		  rk( None )
		}
		else {
		  for(
		    placeNRrscNSubst <- itergen[PlaceInstance](
		      meets
		    )
		  ) {
		    val PlaceInstance( place, Left( rsrc ), s ) = placeNRrscNSubst
		    
		    tweet( "found a resource: " + rsrc )		    
		    
		    consume match {
		      case policy : RetainInCache => {
			channels -= place
		      }
		      case _ => {
			tweet( "policy indicates not to consume from cache: " + place )
		      }
		    }

		    tweet( "Reader departing spaceLock on " + this + " for mget on " + ptn + "." )
		    spaceLock.depart( TheReaderKey )
		    rk( s( rsrc ) )
		    
		    //shift { k : ( Unit => Unit ) => k() }
 		  }
 		}				
	      //}
 	      //tweet( "get returning" )
 	      outerk()
 	    }
 	}
    }
  
  def get( ptn : Pattern ) =
    mget( theMeetingPlace, theWaiters, Cache, Cache )( ptn )
  def fetch( ptn : Pattern ) =
    mget( theMeetingPlace, theWaiters, DoNotRetain, DoNotRetain )( ptn )
  def subscribe( ptn : Pattern ) =
    mget( theChannels, theSubscriptions, Cache, Cache )( ptn )  

  def mputWithSuspension(
    channels : Map[Place,Resource],
    registered : Map[Place,List[RK]],
    consume : Boolean
  )( ptn : Pattern, rsrc : Resource ) : Unit @suspendable = {    
    for( placeNRKsNSubst <- putPlaces( channels, registered, ptn, rsrc ) ) {
      val PlaceInstance( wtr, Right( rks ), s ) = placeNRKsNSubst
      tweet( "waiters waiting for a value at " + wtr + " : " + rks )
      rks match {
	case rk :: rrks => {	
	  if ( consume ) {
	    for( sk <- rks ) {
	      sk match {
		case bk : BlockableContinuation => {
		  bk.unblockOnValue()
		}
		case _ => {
		}
	      }
	      spawn {
		sk( s( rsrc ) )
	      }
	    }
	  }
	  else {
	    registered( wtr ) = rrks
	    rk match {
		case bk : BlockableContinuation => {
		  bk.unblockOnValue()
		}
		case _ => {
		}
	      }
	    rk( s( rsrc ) )
	  }
	}
	case Nil => {
	  channels( wtr ) = rsrc	  
	}
      }
    }
        
  }

  def putPlaces(
    channels : Map[Place,Resource],
    registered : Map[Place,List[RK]],
    ptn : Pattern,
    rsrc : Resource
  )
  : Generator[PlaceInstance,Unit,Unit] = {    
    Generator {
      k : ( PlaceInstance => Unit @suspendable ) => 
	// Are there outstanding waiters at this pattern?    
	tweet(
	  (
	    ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	    + "in putPlaces\n"
	    + "channels : " + channels + "\n"
	    + "registered : " + registered + "\n"
	    + "ptn : " + ptn + "\n"
	    + "rsrc : " + rsrc + "\n"
	    + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	  )
	)

      val map = Right[Map[Place,Resource],Map[Place,List[RK]]]( registered )
      val waitlist = locations( map, ptn )

	waitlist match {
	  // Yes!
	  case waiter :: waiters => {
	    tweet( "found waiters waiting for a value at " + ptn )
	    val itr = waitlist.toList.iterator	    
	    while( itr.hasNext ) {
	      k( itr.next )
	    }
	  }
	  // No...
	  case Nil => {
	    // Store the rsrc at a representative of the ptn
	    tweet( "no waiters waiting for a value at " + ptn )
	    channels( representative( ptn ) ) = rsrc
	  }
	}
    }
  }

  def mput(
    channels : Map[Place,Resource],
    registered : Map[Place,List[RK]],
    consume : Boolean
  )( ptn : Pattern, rsrc : Resource ) : Unit @suspendable = {    
    tweet( "Writer occupying spaceLock on " + this + " for mput on " + ptn + "." )
    /* spaceLock. */ //synchronized {
    //if ( ! spaceLock.available ) {
    //}
    //else {
      spaceLock.occupy( TheWriterKey )
      for( placeNRKsNSubst <- putPlaces( channels, registered, ptn, rsrc ) ) {
	val PlaceInstance( wtr, Right( rks ), s ) = placeNRKsNSubst
	tweet( "waiters waiting for a value at " + wtr + " : " + rks )
	rks match {
	  case rk :: rrks => {	
	    if ( consume ) {
	      for( sk <- rks ) {
		spawn {
		  sk( s( rsrc ) )
		}
	      }
	    }
	    else {
	      registered( wtr ) = rrks
	      rk( s( rsrc ) )
	    }
	  }
	  case Nil => {
	    //channels( wtr ) = rsrc	  
	    //channels( ptn ) = rsrc	  
	    tweet(
	      (
		">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		+ "in mput with empty waitlist; about to put rsrc\n"
		+ "channels : " + channels + "\n"
		+ "registered : " + registered + "\n"
		+ "ptn : " + ptn + "\n"
		+ "rsrc : " + rsrc + "\n"
		+ ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      )
	    )
	    if ( ptn.isInstanceOf[Place] ) {
	      channels( ptn.asInstanceOf[Place] ) = rsrc
	    }
	    else {
	      channels( wtr ) = rsrc
	    }
	    tweet(
	      (
		">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		+ "in mput with empty waitlist; having put rsrc\n"
		+ "channels : " + channels + "\n"
		+ "registered : " + registered + "\n"
		+ "ptn : " + ptn + "\n"
		+ "rsrc : " + rsrc + "\n"
		+ ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      )
	    )
	  }
	}
      }
      
      tweet( "Writer departing spaceLock on " + this + " for mput on " + ptn + "." )
      spaceLock.depart( TheWriterKey )
    //}
    /* spaceLock. */ //synchronized {
    /* spaceLock. */ //notifyAll()
    //}
    //}
    
  }

  def put( ptn : Pattern, rsrc : Resource ) =
    mput( theMeetingPlace, theWaiters, false )( ptn, rsrc )
  def publish( ptn : Pattern, rsrc : Resource ) =
    mput( theChannels, theSubscriptions, true )( ptn, rsrc )
  
}

package usage {
/* ------------------------------------------------------------------
 * Mostly self-contained object to support unit testing
 * ------------------------------------------------------------------ */ 

import java.util.regex.{Pattern => RegexPtn, Matcher => RegexMatcher}

object MonadicRegexTSpace
       extends MonadicTupleSpace[String,String,String]
       with WireTap
       with Journalist
       with ConfiggyReporting
       //with ConfiggyJournal
       with ConfiguredJournal
       with ConfigurationTrampoline
{

  override type Substitution = IdentitySubstitution

  override val theMeetingPlace = new HashMap[String,String]()
  override val theChannels = new HashMap[String,String]()
  override val theWaiters = new HashMap[String,List[RK]]()
  override val theSubscriptions = new HashMap[String,List[RK]]()

  override def tap [A] ( fact : A ) : Unit = {
    reportage( fact )
  }

  override def configFileName : Option[String] = None
  override def configurationDefaults : ConfigurationDefaults = {
    ApplicationDefaults.asInstanceOf[ConfigurationDefaults]
  }

  def representative( ptn : String ) : String = {
    ptn
  }

  def fits( ptn : String, place : String ) : Boolean = {
    RegexPtn.matches( ptn, place ) || RegexPtn.matches( place, ptn )
  }

  def fitsK(
    ptn : String,
    place : String
  ) : Option[Substitution] = {
    //println( "in fitsK on " + this )
    if ( fits( ptn, place ) ) {
      Some( IdentitySubstitution() )
    }
    else {
      None
    }
  }
  
}


}
