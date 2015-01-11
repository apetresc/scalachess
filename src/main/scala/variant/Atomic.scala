package chess
package variant

case object Atomic extends Variant(
  id = 7,
  key = "atomic",
  name = "Atomic",
  shortName = "Atom",
  title = "Nuke your opponent's king to win."
) {

  /** Moves which threaten to explode the opponent's king */
  private def kingThreateningMoves(situation: Situation): Map[Pos, List[Move]] = {

    val moves = for {
      opponentKingPerimeter <- situation.board.kingPosOf(!situation.color) map (_.surroundingPositions)
      myKingPerimeter <- situation.kingPos map (_.surroundingPositions)

      kingAttackingMoves = situation.actors map {
        act =>
          // Filter to moves which take a piece next to the king, exploding the king. The player's king cannot
          // capture, however and it is illegal to capture a piece that would result in your own king exploding
          // (e.g. an opponent piece next to the king)
          val rawMoves = act.trustedMoves(true)

          act.pos -> rawMoves.filter(
            mv => opponentKingPerimeter.contains(mv.dest) &&
              mv.captures && (mv.piece isNot King) &&
              !myKingPerimeter.contains(mv.dest))
      } filter (!_._2.isEmpty)

    } yield kingAttackingMoves.toMap

    moves getOrElse Map.empty
  }

  override def validMoves(situation: Situation): Map[Pos, List[Move]] = {
    // In atomic chess, the pieces have the same roles as usual
    val usualMoves = super.validMoves(situation)

    /* However, it is illegal to make a capture that would result in your own king exploding. */
    val moves = for {
      surroundingKing <- situation.kingPos map (_.surroundingPositions)
      mvs1 = usualMoves.mapValues(_.filter(mv => !mv.captures || mv.captures && !surroundingKing.contains(mv.dest)))
      mvs2 = mvs1.filter(!_._2.isEmpty)
    } yield mvs2

    val kingSafeMoves = moves getOrElse usualMoves

    // Additionally, if the player's king is in check they may prioritise exploding the opponent's king over defending
    // their own
    if (!situation.check) kingSafeMoves else kingSafeMoves ++ kingThreateningMoves(situation)
  }

  override def move(situation: Situation, from: Pos, to: Pos, promotion: Option[PromotableRole]) = for {
    m1 <- super.move(situation, from, to, promotion)
    m2 <- explodeSurroundingPieces(m1).success
  } yield m2

  /** If the move captures, we explode the surrounding pieces. Otherwise, nothing explodes. */
  private def explodeSurroundingPieces(move: Move): Move = {
    if (!move.captures) move
    else {
      val surroundingPositions = move.dest.surroundingPositions
      val afterBoard = move.after
      val destination = move.dest

      val boardPieces = afterBoard.pieces

      // Pawns are immune (for some reason), but all pieces surrounding the captured piece and the capturing piece
      // itself explode
      val piecesToExplode = surroundingPositions.filter(boardPieces.get(_).fold(false)(_.isNot(Pawn))) + destination
      val afterExplosions = boardPieces -- piecesToExplode

      val newBoard = afterBoard withPieces afterExplosions
      move withAfter newBoard
    }
  }

  /**
   * Since a king may walk into the path of another king, it is more difficult to win when your opponent only has a
   * king left.
   */
  private def insufficientAtomicWinningMaterial(board: Board) = {
    val whiteActors = board.actorsOf(White)
    val blackActors = board.actorsOf(Black)
    val allActors = board.actors
    lazy val allPieces = board.actors.values.map(_.piece).filter(_ isNot King)

    // One player must only have their king left
    if (whiteActors.size != 1 && blackActors.size != 1) false
    else {
      // You can mate with a queen, with just one of any other piece or with just a king
      allPieces.size == 1 && !allPieces.exists(_ is Queen) || allActors.size == 2
    }
  }

  override def specialDraw(situation: Situation) = {
    // Bishops on opposite coloured squares can never capture each other to cause a king to explode and a traditional
    // mate would be not be
    val board = situation.board
    InsufficientMatingMaterial.bishopsOnDifferentColor(board) || insufficientAtomicWinningMaterial(board)
  }

  // On insufficient mating material, a win may still be commonly achieved by exploding a piece next to a king
  override def drawsOnInsufficientMaterial = false

  /** Atomic chess has a special end where a king has been killed by exploding with an adjacent captured piece */
  override def specialEnd(situation: Situation) = situation.board.kingPos.size != 2
}
