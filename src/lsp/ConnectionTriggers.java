package lsp;

interface ConnectionTriggers {
	/**
	 * Callback representando as ações a serem disparadas a cada época
	 */
	void doEpochActions();

	/**
	 * Callback representando as ações de fechamento da conexão
	 */
	void doCloseConnection();

	final class Helpers {
		// Classe utilitária, nunca instanciável
		private Helpers() {
		}

		static void resendData(final LspSocket sock, final LspConnection conn) {
			InternalPack pack = conn.sent();
			if (pack != null) {
				sock.dgramSendData(pack);
			}
		}

		/*
		 * Se foi recebida alguma mensagem de dados, então envia o ACK dessa
		 * mensagem, senão envia envia ACK(seqNum=0)
		 */
		static void resendAck(final LspSocket sock, final LspConnection conn) {
			short seqNum = conn.receivedSeqNum();
			if (seqNum != -1) {
				sock.dgramSendAck(conn, seqNum);
			} else {
				sock.dgramSendAck(conn, (short) 0);
			}
		}
	}
}
