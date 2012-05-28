/*
   Copyright 2012 Denis Bardadym

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package code.snippet

import bootstrap.liftweb._
import net.liftweb._
import util.Helpers._
import http._
import common._

import js.{JsCmd, JE}
import js.jquery._
import js.jquery.JqJE._
import js.jquery.JqJsCmds._

import code.model._
import code.lib._
import code.lib.Sitemap._
import SnippetHelper._
import xml._

import com.foursquare.rogue.Rogue._

/**
 * User: denis.bardadym
 * Date: 9/19/11
 * Time: 2:16 PM
 */

class UserOps(user: UserDoc) extends Loggable with RepositoryUI {

  private var name = ""
  private var open_? = true

  def saveRepo(): JsCmd = {

    val repo = RepositoryDoc.createRecord.ownerId(user.id.get).name(name).open_?(open_?)

    repo.validate match {
        case Nil => {
          repo.save

          cleanForm("form") &
          updateJs()
        }
        case l => l.foreach(fe => S.error("repo", fe.msg))
    }
  }

  def updateJs(): JsCmd = {
    Jq(".repo_list") ~> JqHtml(memo.applyAgain()) &
    JE.JsRaw("setCloneUrls()") 
  }

  def addNewRepo: NodeSeq => NodeSeq = 
    (for { 
       currentUser <- UserDoc.currentUser

       if(user.login.get == currentUser.login.get)
     } yield {   
       ".form" #> SHtml.ajaxForm(
          SHtml.text("", v => name = v.trim, "placeholder" -> "Name", "class" -> "textfield large") ++
          <br/> ++
          <label>{SHtml.checkbox(true, open_? = _)} Public repo?</label> ++
          <br/> ++
          SHtml.button("Add", DoNothing, "class" -> "button") ++
          SHtml.hidden(saveRepo _)
        )
     }) openOr cleanAll

  def repos = user.publicRepos ++ 
                   (if(user.is(UserDoc.currentUser)) 
                    user.privateRepos ++ user.collaboratedRepos 
                    else Nil)
              

  val memo = SHtml.memoize {
    if(repos.isEmpty) ".repo_holder" #> <p class="large">{user.login.get.capitalize} has no repositories.</p>
    else
    ".repo" #> repos.map(repo =>
       renderRepositoryBlock(repo, 
                            user, 
                            r => a(defaultTree.calcHref(r), 
                              if(r.ownerId.get == user.id.get) Text(r.name.get)
                              else Text(r.owner.login.get + "/" + r.name.get)), 
                            r => updateJs(),
                            r => S.redirectTo(userRepos.calcHref(UserDoc.currentUser.get))) )
  }
 

  def renderRepositoryList = {   

    //if(repos.isEmpty)
    //  passThru
    //else 
     ".repo_list *" #> memo    
    
  }

  def userStat = {
    ".user_login *" #> ("User " + user.login.get.capitalize) &
    ".user_email *" #> ( "a" #> <a href={"mailto:" + user.email.get}>{user.email.get}</a> ) &
    ".silly_stat *" #> 
    (<li>{user.publicRepos.size} public</li> ++
    (UserDoc.currentUser match {
      case Full(u) if u.login.get == user.login.get => 
        <li>{user.privateRepos.size} private</li> ++
        <li>{user.collaboratedRepos.size} collaborated</li>
      case _ => NodeSeq.Empty
    }))

  }

  def acivityOnWatchingStream = {
    val subscriptions = (NotifySubscriptionDoc where (_.who eqs user.id.get) fetch) filter (_.output.get.web.get.activated.get)
    val events = subscriptions.flatMap(s => PushEventDoc where (_.repo eqs s.repo.get) fetch).sortBy(_.when.get)
    logger.debug("Push events" + events)
    ".notify_stream *" #> events.map(e => 
        (".username" #> e.who.obj.map(u => <a href={userRepos.calcHref(u)}>{u.login.get}</a>).openOr(Text("Someone")) &
        ".reponame" #> e.repo.obj.map(o => <a href={defaultTree.calcHref(o)}>{o.name.get}</a>).openOr(Text("something")) &
        (if(!e.added.get.isEmpty) {
          val lst: List[NodeSeq] = e.added.get.map { b => 
            val shortName = b.split("/").last
            e.repo.obj.map { o => 
              <a href={treeAtCommit.calcHref(SourceElement.rootAt(o, shortName))}>{shortName}</a>
            }.openOr(Text(shortName))
          }
          ".addedbranches" #> (".branches *" #> lst.reduce(_ ++ Text(", ") ++ _))
        } else ".addedbranches" #> NodeSeq.Empty) &
        (if(!e.deleted.get.isEmpty) {
          val lst: List[NodeSeq] = e.deleted.get.map { b => 
            val shortName = b.split("/").last
            e.repo.obj.map { o => 
              <a href={treeAtCommit.calcHref(SourceElement.rootAt(o, shortName))}>{shortName}</a>
            }.openOr(Text(shortName))
          }
          ".deletedbranches" #> (".branches *" #> lst.reduce(_ ++ Text(", ") ++ _))
        } else ".deletedbranches" #> NodeSeq.Empty) &
        (if(!e.changed.get.isEmpty) {
          ".changedbranches" #> (".branch *" #> e.changed.get.map( b =>
            ".branchname" #> {
              val shortName = b.name.get.split("/").last
              e.repo.obj.map { o => 
                <a href={treeAtCommit.calcHref(SourceElement.rootAt(o, shortName))}>{shortName}</a>
              }.openOr(Text(shortName))
            } &
            "li *" #> b.commits.get.map(c =>
              ".link [href]" #> commit.calcHref(SourceElement.rootAt(e.repo.obj.get, c.hash.get)) &
              ".hash *" #> c.hash.get.substring(0, 6) &
              ".msg *" #> c.msg.get
            )
          ))
        } else ".changedbranches" #> NodeSeq.Empty))(pushEventTpl)
    )
  }

  val pushEventTpl: NodeSeq = 
      <div class="push_event notify_event">
        <div><span class="username"></span> make a push in <span class="reponame"></span>.</div>
        <div class="addedbranches">Branches added: <span class="branches"/>.</div>
        <div class="deletedbranches">Branches deleted: <span class="branches"/>.</div>
        <div class="changedbranches">
          <div class="branch">Change in <span class="branchname"/>:
            <ul>
              <li><a class="link" href=""><span class="hash">1das12</span></a> <span class="msg">Message of commit</span></li>
            </ul>
          </div>
        </div>
      </div>
}

