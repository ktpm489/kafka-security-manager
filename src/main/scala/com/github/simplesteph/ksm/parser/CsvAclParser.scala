package com.github.simplesteph.ksm.parser

import java.io.{ OutputStream, Reader }

import com.github.simplesteph.ksm.source.SourceAclResult
import com.github.tototoshi.csv.{ CSVFormat, CSVReader, CSVWriter, QUOTE_MINIMAL, Quoting }
import kafka.security.auth._
import org.apache.kafka.common.utils.SecurityUtils

import scala.collection.immutable
import scala.util.Try

/**
 * Parser that assumes that all ACLs are flattened
 * and live under a CSV format.
 * The CSV is expected to have headers as outlined below and in the example
 * Empty lines in the CSV should be ignored
 */
object CsvAclParser extends AclParser {

  final val KAFKA_PRINCIPAL_COL = "KafkaPrincipal"
  final val RESOURCE_TYPE_COL = "ResourceType"
  final val RESOURCE_NAME_COL = "ResourceName"
  final val OPERATION_COL = "Operation"
  final val PERMISSION_TYPE_COL = "PermissionType"
  final val HOST_COL = "Host"

  final val EXPECTED_COLS = List(
    KAFKA_PRINCIPAL_COL,
    RESOURCE_TYPE_COL, RESOURCE_NAME_COL, OPERATION_COL,
    PERMISSION_TYPE_COL, HOST_COL)

  // we treat empty lines as Nil hence the format override
  implicit val csvFormat: CSVFormat = new CSVFormat {
    val delimiter: Char = ','
    val quoteChar: Char = '"'
    val escapeChar: Char = '"'
    val lineTerminator: String = "\r\n"
    val quoting: Quoting = QUOTE_MINIMAL
    val treatEmptyLineAsNil: Boolean = true
  }

  /**
   * parse a row to return an ACL
   * @param row a map of column name to row value
   * @return an ACL
   */
  def parseRow(row: Map[String, String]): (Resource, Acl) = {
    val kafkaPrincipal = SecurityUtils.parseKafkaPrincipal(row(KAFKA_PRINCIPAL_COL))
    val resourceType = ResourceType.fromString(row(RESOURCE_TYPE_COL))
    val resourceName = row(RESOURCE_NAME_COL)
    val operation = Operation.fromString(row(OPERATION_COL))
    val permissionType = PermissionType.fromString(row(PERMISSION_TYPE_COL))
    val host = row(HOST_COL)

    val resource = Resource(resourceType, resourceName)
    val acl = Acl(kafkaPrincipal, permissionType, host, operation)

    (resource, acl)
  }

  /**
   * Parses all the ACL as provided by the reader that wraps the CSV content
   * @param reader we use the reader interface to use string and files interchangeably in the parser
   * @return sourceAclResult
   */
  override def aclsFromReader(reader: Reader): SourceAclResult = {
    val csv = CSVReader.open(reader).allWithHeaders().filter(_.nonEmpty)

    // parse the CSV
    val parsed: immutable.List[Try[(Resource, Acl)]] = csv.map(row => Try {
      parseRow(row)
    }.recoverWith[(Resource, Acl)] { case (t: Throwable) => Try(throw new CsvParserException(row, t)) })

    val acls = parsed.filter(_.isSuccess).map(_.get).toSet
    val errors = parsed.filter(_.isFailure).map(_.failed)
    SourceAclResult(acls, errors)
  }

  def asCsv(r: Resource, a: Acl): String = {
    List(
      a.principal.toString,
      r.resourceType.toString,
      r.name,
      a.operation.toString,
      a.permissionType.toString,
      a.host).mkString(",")
  }

  override def formatAcls(acls: List[(Resource, Acl)]): String = {
    val sb = new StringBuilder
    // header
    sb.append(EXPECTED_COLS.mkString(","))
    sb.append(System.getProperty("line.separator"))
    // rows
    acls.foreach {
      case (r, a) =>
        sb.append(asCsv(r, a))
        sb.append(System.getProperty("line.separator"))
    }
    sb.toString()
  }

}

